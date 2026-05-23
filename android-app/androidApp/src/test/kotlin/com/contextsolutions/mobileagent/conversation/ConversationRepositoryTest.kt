package com.contextsolutions.mobileagent.conversation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.mobileagent.agent.ChatMessage
import com.contextsolutions.mobileagent.agent.ToolCall
import com.contextsolutions.mobileagent.db.MobileAgentDatabase
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Schema + DAO coverage for [SqlDelightConversationRepository]. Runs against
 * an in-memory JDBC SQLite database so the SQLDelight schema is exercised
 * end-to-end without needing an Android Context.
 *
 * Companion to [ConversationsMigrationTest] (schema correctness) — this
 * test focuses on repository behaviour: title derivation, 50-cap eviction,
 * oldest-pair deletion semantics, ack idempotency, and message round-trip
 * (Assistant tool-call, Tool result).
 */
class ConversationRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MobileAgentDatabase
    private lateinit var repo: SqlDelightConversationRepository

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MobileAgentDatabase.Schema.create(driver)
        // AndroidSqliteDriver enables FKs automatically on Android API 16+;
        // JdbcSqliteDriver does not, so explicitly turn them on so the
        // ON DELETE CASCADE on messages.conversation_id fires under test.
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        db = MobileAgentDatabase(driver)
        repo = SqlDelightConversationRepository(
            queries = db.conversationsQueries,
            counters = NoOpTelemetryCounters,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun deriveTitle_truncates_to_40_chars_with_ellipsis() {
        val long = "this is a long message that goes well past the forty character limit"
        val title = ConversationRepository.deriveTitle(long)
        assertEquals(40, title.length)
        assertTrue("ellipsis suffix missing", title.endsWith("…"))
    }

    @Test
    fun deriveTitle_passes_through_short_messages() {
        assertEquals("hello world", ConversationRepository.deriveTitle("  hello world  "))
    }

    @Test
    fun deriveTitle_collapses_internal_whitespace() {
        assertEquals("a b c", ConversationRepository.deriveTitle("a   b\n\nc"))
    }

    @Test
    fun deriveTitle_empty_input_falls_back_to_default() {
        assertEquals("New conversation", ConversationRepository.deriveTitle(""))
        assertEquals("New conversation", ConversationRepository.deriveTitle("   "))
    }

    @Test
    fun create_and_listRecent_round_trip() = runTest {
        repo.create(id = "c1", title = "first", nowEpochMs = 100L)
        repo.create(id = "c2", title = "second", nowEpochMs = 200L)

        val recent = repo.listRecent()
        assertEquals(2, recent.size)
        // Sorted newest-first by updated_at.
        assertEquals("c2", recent[0].id)
        assertEquals("c1", recent[1].id)
        assertNull(recent[0].truncationAcknowledgedAtEpochMs)
    }

    @Test
    fun listRecent_includes_last_message_preview_for_user_assistant_only() = runTest {
        repo.create("c1", "title", nowEpochMs = 100L)
        repo.appendMessage("c1", ChatMessage.User("first user msg"), nowEpochMs = 101L)
        repo.appendMessage("c1", ChatMessage.Assistant("first assistant reply"), nowEpochMs = 102L)
        repo.appendMessage(
            conversationId = "c1",
            message = ChatMessage.Tool(callId = "x", toolName = "web_search", text = "raw results"),
            nowEpochMs = 103L,
        )

        val preview = repo.listRecent().single().lastMessagePreview
        // Tool messages are excluded — the preview comes from the assistant reply.
        assertEquals("first assistant reply", preview)
    }

    @Test
    fun appendMessage_assigns_monotonic_sequence_indices() = runTest {
        repo.create("c1", "title", nowEpochMs = 100L)
        val s0 = repo.appendMessage("c1", ChatMessage.User("u1"), nowEpochMs = 101L)
        val s1 = repo.appendMessage("c1", ChatMessage.Assistant("a1"), nowEpochMs = 102L)
        val s2 = repo.appendMessage("c1", ChatMessage.User("u2"), nowEpochMs = 103L)
        assertEquals(0L, s0)
        assertEquals(1L, s1)
        assertEquals(2L, s2)
    }

    @Test
    fun loadMessages_round_trips_user_assistant_tool_with_tool_call() = runTest {
        repo.create("c1", "title", 100L)
        val original = listOf(
            ChatMessage.User("what's the weather?"),
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall(
                    callId = "call-1",
                    name = "web_search",
                    argumentsJson = """{"query":"weather today"}""",
                ),
            ),
            ChatMessage.Tool(callId = "call-1", toolName = "web_search", text = "sunny"),
            ChatMessage.Assistant(text = "It's sunny today."),
        )
        for ((i, msg) in original.withIndex()) {
            repo.appendMessage("c1", msg, nowEpochMs = 101L + i)
        }

        val loaded = repo.loadMessages("c1")
        assertEquals(4, loaded.size)
        assertEquals(ChatMessage.User("what's the weather?"), loaded[0])
        val asst1 = loaded[1] as ChatMessage.Assistant
        assertNotNull(asst1.toolCall)
        assertEquals("call-1", asst1.toolCall!!.callId)
        assertEquals("web_search", asst1.toolCall!!.name)
        val tool = loaded[2] as ChatMessage.Tool
        assertEquals("call-1", tool.callId)
        assertEquals("web_search", tool.toolName)
        assertEquals("sunny", tool.text)
        assertEquals(ChatMessage.Assistant(text = "It's sunny today."), loaded[3])
    }

    @Test
    fun loadMessages_round_trips_user_image_bytes() = runTest {
        // PR #49 — an attached photo (BLOB) persists and re-loads on a user turn.
        repo.create("c1", "title", 100L)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10, 0x20, 0x30)
        repo.appendMessage("c1", ChatMessage.User("look at this", imageBytes = jpeg), 101L)
        repo.appendMessage("c1", ChatMessage.User("text only"), 102L)

        val loaded = repo.loadMessages("c1")
        val withImage = loaded[0] as ChatMessage.User
        assertEquals("look at this", withImage.text)
        assertNotNull("image bytes should round-trip", withImage.imageBytes)
        assertArrayEquals(jpeg, withImage.imageBytes)
        // A text-only user turn keeps null bytes (no empty BLOB written).
        assertNull((loaded[1] as ChatMessage.User).imageBytes)
    }

    @Test
    fun deleteOldestPair_drops_first_user_assistant_with_interleaved_tool() = runTest {
        repo.create("c1", "title", 100L)
        // Pair 1: user → assistant(tool_call) → tool → assistant
        repo.appendMessage("c1", ChatMessage.User("q1"), 101L)
        repo.appendMessage(
            "c1",
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall("k1", "web_search", "{}"),
            ),
            102L,
        )
        repo.appendMessage("c1", ChatMessage.Tool("k1", "web_search", "result"), 103L)
        repo.appendMessage("c1", ChatMessage.Assistant("answer 1"), 104L)
        // Pair 2: user → assistant
        repo.appendMessage("c1", ChatMessage.User("q2"), 105L)
        repo.appendMessage("c1", ChatMessage.Assistant("answer 2"), 106L)

        val deleted = repo.deleteOldestPair("c1")
        assertEquals(4, deleted) // user + assistant_with_call + tool + assistant

        val remaining = repo.loadMessages("c1")
        assertEquals(2, remaining.size)
        assertEquals(ChatMessage.User("q2"), remaining[0])
        assertEquals(ChatMessage.Assistant("answer 2"), remaining[1])
    }

    @Test
    fun deleteOldestPair_noops_when_fewer_than_two_user_messages() = runTest {
        repo.create("c1", "title", 100L)
        repo.appendMessage("c1", ChatMessage.User("only"), 101L)
        repo.appendMessage("c1", ChatMessage.Assistant("reply"), 102L)

        val deleted = repo.deleteOldestPair("c1")
        assertEquals(0, deleted)
        assertEquals(2, repo.loadMessages("c1").size)
    }

    @Test
    fun acknowledgeTruncation_persists_timestamp() = runTest {
        repo.create("c1", "title", 100L)
        repo.acknowledgeTruncation("c1", nowEpochMs = 200L)

        val record = repo.get("c1")
        assertEquals(200L, record?.truncationAcknowledgedAtEpochMs)
    }

    @Test
    fun acknowledgeTruncation_is_idempotent_and_overwrites_timestamp() = runTest {
        repo.create("c1", "title", 100L)
        repo.acknowledgeTruncation("c1", 200L)
        repo.acknowledgeTruncation("c1", 300L)

        assertEquals(300L, repo.get("c1")?.truncationAcknowledgedAtEpochMs)
    }

    @Test
    fun create_evicts_oldest_when_cap_exceeded() = runTest {
        // Build the store up to exactly the cap, then add one more.
        for (i in 1..ConversationRepository.CONVERSATION_CAP) {
            repo.create(id = "c$i", title = "t$i", nowEpochMs = 1000L + i)
        }
        repo.create(id = "c-overflow", title = "newest", nowEpochMs = 9_999L)

        val remaining = repo.listRecent(limit = 100).map { it.id }
        assertEquals(ConversationRepository.CONVERSATION_CAP, remaining.size)
        // c1 was the oldest and must be evicted; c-overflow is newest and must remain.
        assertFalse("c1 should have been evicted", remaining.contains("c1"))
        assertTrue("c-overflow should be present", remaining.contains("c-overflow"))
    }

    @Test
    fun delete_removes_conversation_and_cascades_messages() = runTest {
        repo.create("c1", "title", 100L)
        repo.appendMessage("c1", ChatMessage.User("u"), 101L)
        repo.appendMessage("c1", ChatMessage.Assistant("a"), 102L)

        repo.delete("c1")

        assertNull(repo.get("c1"))
        assertTrue(repo.loadMessages("c1").isEmpty())
    }

    @Test
    fun evictToCap_returns_zero_when_under_cap() = runTest {
        repo.create("c1", "t", 100L)
        repo.create("c2", "t", 200L)
        assertEquals(0, repo.evictToCap(maxConversations = 50))
    }

    @Test
    fun loadMessages_round_trips_assistant_citations() = runTest {
        repo.create("c1", "title", 100L)
        val sources = listOf(
            SearchSource(
                title = "Toronto weather",
                url = "https://weather.example.com/toronto",
                snippet = "sunny, 18°C",
            ),
            SearchSource(
                title = "Breaking news",
                url = "https://news.example.com/x",
                snippet = "developing story",
                age = "10 minutes ago",
                breaking = true,
            ),
        )
        repo.appendMessage("c1", ChatMessage.User("what's the weather?"), 101L)
        repo.appendMessage(
            "c1",
            ChatMessage.Assistant(text = "It's sunny.", citations = sources),
            102L,
        )

        val loaded = repo.loadMessages("c1")
        val asst = loaded.filterIsInstance<ChatMessage.Assistant>().single()
        assertEquals(2, asst.citations.size)
        assertEquals(sources, asst.citations)
    }

    @Test
    fun loadMessages_returns_empty_citations_for_pre_pr13_rows() = runTest {
        // Simulates a row written before citations persistence shipped:
        // tool_result_json is NULL on an assistant row, which used to be the
        // norm. Round-trip must yield an empty list (not an error).
        repo.create("c1", "title", 100L)
        repo.appendMessage("c1", ChatMessage.Assistant(text = "plain reply"), 101L)

        val loaded = repo.loadMessages("c1").single() as ChatMessage.Assistant
        assertTrue(loaded.citations.isEmpty())
    }
}
