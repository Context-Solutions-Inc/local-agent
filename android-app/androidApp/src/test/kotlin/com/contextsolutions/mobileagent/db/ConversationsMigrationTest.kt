package com.contextsolutions.mobileagent.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * PR#13 migration regression for `3.sqm` (v3 → v4): adds the
 * `truncation_acknowledged_at` column to `conversations`.
 *
 * Strategy mirrors [MemoriesMigrationTest]: stand up the v3 schema directly
 * via raw DDL (independent of the snapshot .db files), seed a representative
 * conversation, run `Schema.migrate(3, 4)`, and assert the column exists
 * with the expected default (NULL).
 */
class ConversationsMigrationTest {

    private lateinit var driver: JdbcSqliteDriver

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun migrate_three_to_four_adds_truncation_ack_column_with_null_default() {
        createV3Schema(driver)
        insertV3Conversation(
            driver,
            id = "c1",
            title = "i live in toronto",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
        )

        val result = MobileAgentDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)
        assertEquals(QueryResult.Unit, result)

        val ack = selectTruncationAckForId(driver, "c1")
        assertNull("truncation_acknowledged_at should default to NULL on existing rows", ack)
    }

    @Test
    fun migrated_conversations_table_accepts_truncation_ack_writes() {
        createV3Schema(driver)
        MobileAgentDatabase.Schema.migrate(driver, oldVersion = 3, newVersion = 4)

        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO conversations(id, title, created_at_epoch_ms, updated_at_epoch_ms, truncation_acknowledged_at)
                VALUES ('c2', 'short', 1700000000000, 1700000000001, 1700000000050);
            """.trimIndent(),
            parameters = 0,
        )

        val ack = selectTruncationAckForId(driver, "c2")
        assertNotNull("ack timestamp must round-trip after migration", ack)
        assertEquals(1_700_000_000_050L, ack)
    }

    @Test
    fun migrate_v1_to_v4_chains_through_all_sqm_files() {
        // M0/M1/M2/M4 installs land at v1 with the original memories table
        // and the conversations stub. AndroidSqliteDriver calls migrate(1, 4)
        // which must run 1.sqm, 2.sqm, and 3.sqm in order.
        createV1Schema(driver)
        insertV1Conversation(driver, id = "c1", title = "early chat")

        val result = MobileAgentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 4)
        assertEquals(QueryResult.Unit, result)

        // The new column exists and is NULL for pre-existing rows.
        val ack = selectTruncationAckForId(driver, "c1")
        assertNull(ack)
    }

    @Test
    fun migrate_five_to_six_adds_image_bytes_column_with_null_default() {
        // PR #49 — 5.sqm adds messages.image_bytes BLOB (persisted photo).
        createV5Schema(driver)
        insertV5Message(driver, id = "m1", convId = "c1", content = "hello")

        val result = MobileAgentDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = 6)
        assertEquals(QueryResult.Unit, result)

        val bytes = selectImageBytesForMessage(driver, "m1")
        assertNull("image_bytes should default to NULL on existing rows", bytes)
    }

    @Test
    fun migrated_messages_table_accepts_image_bytes_writes() {
        createV5Schema(driver)
        MobileAgentDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = 6)

        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO messages(id, conversation_id, role, content, created_at_epoch_ms, sequence_index, image_bytes) " +
                "VALUES ('m2', 'c1', 'user', 'look', 1700000000000, 0, ?);",
            parameters = 1,
        ) {
            bindBytes(0, jpeg)
        }

        val bytes = selectImageBytesForMessage(driver, "m2")
        assertNotNull("image_bytes must round-trip after migration", bytes)
        assertArrayEquals(jpeg, bytes)
    }

    // -- v5 fixture (post-4.sqm, pre-5.sqm) ---------------------------------
    // Only `messages` + its parent `conversations` are needed for 5.sqm's ALTER.

    private fun createV5Schema(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
                CREATE TABLE conversations (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    truncation_acknowledged_at INTEGER
                );
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
                CREATE TABLE messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tool_call_json TEXT,
                    tool_result_json TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    sequence_index INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX messages_by_conversation ON messages(conversation_id, sequence_index);", 0)
        driver.execute(
            null,
            "INSERT INTO conversations(id, title, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('c1', 'photo chat', 1700000000000, 1700000000000);",
            0,
        )
    }

    private fun insertV5Message(driver: JdbcSqliteDriver, id: String, convId: String, content: String) {
        driver.execute(
            null,
            "INSERT INTO messages(id, conversation_id, role, content, created_at_epoch_ms, sequence_index) " +
                "VALUES ('$id', '$convId', 'user', '$content', 1700000000000, 0);",
            0,
        )
    }

    private fun selectImageBytesForMessage(driver: JdbcSqliteDriver, id: String): ByteArray? {
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT image_bytes FROM messages WHERE id = '$id';",
            parameters = 0,
            mapper = { cursor ->
                if (cursor.next().value) {
                    QueryResult.Value(cursor.getBytes(0))
                } else {
                    QueryResult.Value(null)
                }
            },
        ).value
    }

    // -- v3 fixture (post-2.sqm, pre-3.sqm) ---------------------------------

    private fun createV3Schema(driver: JdbcSqliteDriver) {
        // conversations + messages at the M0/v3 shape — no truncation_ack column.
        driver.execute(
            null,
            """
                CREATE TABLE conversations (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
                CREATE TABLE messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tool_call_json TEXT,
                    tool_result_json TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    sequence_index INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX messages_by_conversation ON messages(conversation_id, sequence_index);", 0)
    }

    private fun insertV3Conversation(
        driver: JdbcSqliteDriver,
        id: String,
        title: String,
        createdAt: Long,
        updatedAt: Long,
    ) {
        driver.execute(
            null,
            "INSERT INTO conversations(id, title, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('$id', '$title', $createdAt, $updatedAt);",
            0,
        )
    }

    // -- v1 fixture (pre-1.sqm; only used by the full chain test) -----------

    private fun createV1Schema(driver: JdbcSqliteDriver) {
        driver.execute(
            null,
            """
                CREATE TABLE memories (
                    id TEXT NOT NULL PRIMARY KEY,
                    text TEXT NOT NULL,
                    category TEXT NOT NULL,
                    conversation_id TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    last_accessed_epoch_ms INTEGER NOT NULL,
                    embedding BLOB NOT NULL,
                    expires_at_epoch_ms INTEGER
                );
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX memories_by_category ON memories(category);", 0)
        driver.execute(
            null,
            "CREATE INDEX memories_by_expires ON memories(expires_at_epoch_ms) " +
                "WHERE expires_at_epoch_ms IS NOT NULL;",
            0,
        )
        driver.execute(
            null,
            """
                CREATE TABLE conversations (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
                CREATE TABLE messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    tool_call_json TEXT,
                    tool_result_json TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    sequence_index INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX messages_by_conversation ON messages(conversation_id, sequence_index);", 0)
        // M1 stub telemetry_counters table (dropped by 2.sqm) so the chain
        // migration's DROP TABLE has something to drop and we exercise that
        // pre-condition.
        driver.execute(
            null,
            """
                CREATE TABLE telemetry_counters (
                    name TEXT NOT NULL PRIMARY KEY,
                    value INTEGER NOT NULL
                );
            """.trimIndent(),
            0,
        )
    }

    private fun insertV1Conversation(driver: JdbcSqliteDriver, id: String, title: String) {
        driver.execute(
            null,
            "INSERT INTO conversations(id, title, created_at_epoch_ms, updated_at_epoch_ms) " +
                "VALUES ('$id', '$title', 1700000000000, 1700000000000);",
            0,
        )
    }

    private fun selectTruncationAckForId(driver: JdbcSqliteDriver, id: String): Long? {
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT truncation_acknowledged_at FROM conversations WHERE id = '$id';",
            parameters = 0,
            mapper = { cursor ->
                if (cursor.next().value) {
                    QueryResult.Value(cursor.getLong(0))
                } else {
                    QueryResult.Value(null)
                }
            },
        ).value
    }
}
