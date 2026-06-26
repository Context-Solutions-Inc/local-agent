package com.contextsolutions.localagent.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase A regression for the SQLDelight v1 → v2 migration in
 * `:shared/commonMain/sqldelight/.../db/1.sqm` (adds `memories.access_count`).
 *
 * Strategy: stand up an in-memory JDBC SQLite DB at the v1 schema state
 * (memories table WITHOUT `access_count`), insert a representative row,
 * then call `LocalAgentDatabase.Schema.migrate(driver, 1, 2)` — the same
 * entry point `AndroidSqliteDriver` uses on upgrade detection — and
 * assert:
 *
 *   * `Schema.version == 2` after we add the .sqm file (proves the
 *     SQLDelight code-gen picked it up).
 *   * Post-migration row preserves all v1 columns.
 *   * Post-migration `access_count` defaults to 0 on existing rows.
 *   * Post-migration writes accept and round-trip `access_count` values.
 *
 * This test does NOT depend on a committed `.db` schema snapshot; it
 * recreates the v1 shape directly via raw DDL so the migration logic
 * itself is exercised on every CI run.
 */
class MemoriesMigrationTest {

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
    fun schema_version_reflects_all_sqm_migrations() {
        // SQLDelight derives Schema.version from the highest .sqm filename + 1.
        // M6 Phase A added 1.sqm (memories.access_count), Phase C added 2.sqm
        // (telemetry tables), PR#13 added 3.sqm (conversations.truncation_
        // acknowledged_at), PR#15 added 4.sqm (todos table), PR#49 added 5.sqm
        // (messages.image_bytes), PR#50 added 6.sqm (messages.render_markdown),
        // the desktop port's Phase 7 added 7.sqm (tasks table), PR#57 added
        // 8.sqm (conversations/memories soft-delete tombstones + memory updated_at
        // for sync), PR#70 added 9.sqm (jobs + job_runs), PR#99 added 10.sqm
        // (rename the todos table -> mylist), PR#4 added 11.sqm
        // (messages.deleted_at_epoch_ms soft-delete tombstone), and PR#22 added
        // 12.sqm (mylist.deleted_at_epoch_ms soft-delete tombstone for sync) —
        // current version must be 13. If this fails after a new schema change, either
        // the SQLDelight build didn't regenerate the schema or the new .sqm is misnamed.
        assertEquals(13L, LocalAgentDatabase.Schema.version)
    }

    @Test
    fun migrate_one_to_two_adds_access_count_column_with_default_zero() {
        createV1MemoriesTable(driver)
        insertV1MemoryRow(
            driver,
            id = "m1",
            text = "user lives in toronto",
            category = "personal_identity",
            createdAt = 1_700_000_000_000L,
            lastAccessed = 1_700_000_000_000L,
        )

        // Run the same Schema.migrate(...) AndroidSqliteDriver would call on
        // upgrade detection.
        val result = LocalAgentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 2)
        assertEquals(QueryResult.Unit, result)

        // The column must exist with DEFAULT 0; the existing row must reflect that.
        val accessCount = selectAccessCountForId(driver, id = "m1")
        assertNotNull("access_count column missing after migration", accessCount)
        assertEquals(0L, accessCount)
    }

    @Test
    fun migrated_table_accepts_inserts_with_explicit_access_count() {
        createV1MemoriesTable(driver)
        LocalAgentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 2)

        // After migration, INSERT statements that mention access_count must
        // succeed — confirms the column has the expected NOT NULL semantics
        // and accepts integer values.
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO memories(
                    id, text, category, conversation_id,
                    created_at_epoch_ms, last_accessed_epoch_ms, access_count,
                    embedding, expires_at_epoch_ms
                ) VALUES ('m2', 'user likes hiking', 'interest', NULL,
                          1700000000000, 1700000000000, 7,
                          X'00000000', NULL);
            """.trimIndent(),
            parameters = 0,
        )

        assertEquals(7L, selectAccessCountForId(driver, id = "m2"))
    }

    @Test
    fun migrate_preserves_existing_columns_and_values() {
        createV1MemoriesTable(driver)
        insertV1MemoryRow(
            driver,
            id = "m1",
            text = "the user is a software engineer",
            category = "professional",
            conversationId = "conv-1",
            createdAt = 1_700_000_000_000L,
            lastAccessed = 1_710_000_000_000L,
            expiresAt = 1_720_000_000_000L,
        )

        LocalAgentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 2)

        val (text, category, conversationId, created, lastAccessed, expires) =
            selectV1FieldsForId(driver, "m1")
        assertEquals("the user is a software engineer", text)
        assertEquals("professional", category)
        assertEquals("conv-1", conversationId)
        assertEquals(1_700_000_000_000L, created)
        assertEquals(1_710_000_000_000L, lastAccessed)
        assertEquals(1_720_000_000_000L, expires)
    }

    @Test
    fun migrate_is_idempotent_when_invoked_via_create_then_migrate() {
        // Fresh installs land at the current version via Schema.create();
        // calling migrate(current, current) afterwards must be a no-op rather
        // than re-running .sqm files (which would fail with "duplicate column"
        // / "duplicate table"). Defends against future driver wiring that
        // mistakenly triggers a same-version migration.
        LocalAgentDatabase.Schema.create(driver)
        val noOp = LocalAgentDatabase.Schema.migrate(
            driver = driver,
            oldVersion = 4,
            newVersion = 4,
        )
        assertEquals(QueryResult.Unit, noOp)
    }

    @Test
    fun migrate_v1_to_v3_chains_through_all_sqm_files() {
        // Existing pre-M5 installs (M0/M1/M2/M4) hit this path: DB on disk
        // is at v1 (no access_count, no telemetry tables), Schema.version
        // is 3. AndroidSqliteDriver calls Schema.migrate(1, 3) which must
        // run 1.sqm then 2.sqm in order.
        createV1MemoriesTable(driver)
        insertV1MemoryRow(
            driver,
            id = "m1",
            text = "i live in toronto",
            category = "personal_identity",
            createdAt = 1_700_000_000_000L,
            lastAccessed = 1_700_000_000_000L,
        )

        val result = LocalAgentDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3)
        assertEquals(QueryResult.Unit, result)

        // v1→v2 added access_count to memories: existing row preserved at 0.
        val accessCount = selectAccessCountForId(driver, id = "m1")
        assertNotNull("access_count column missing after v1→v3 migration", accessCount)
        assertEquals(0L, accessCount)

        // v2→v3 dropped the M1 stub telemetry_counters table and created the
        // M6 Phase C tables. Verify the new tables exist by inserting a row.
        driver.execute(
            identifier = null,
            sql = "INSERT INTO telemetry_aggregate(window_start_epoch_ms, counter_name, counter_value) " +
                "VALUES (0, 'queries_total', 5);",
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = "INSERT INTO telemetry_latency_aggregate(window_start_epoch_ms, metric_name, p50_ms, p95_ms, p99_ms, sample_count) " +
                "VALUES (0, 'first_token_ms', 100, 200, 250, 10);",
            parameters = 0,
        )
    }

    // -- v1 schema fixture --------------------------------------------------

    private fun createV1MemoriesTable(driver: JdbcSqliteDriver) {
        // Exact pre-M5 (M0/M1/M2/M4) schema. NO access_count column. Only the
        // two indexes that shipped at v1; the other two (`memories_by_conversation`
        // and `memories_by_last_accessed`) are added by the v1 → v2 migration in
        // 1.sqm.
        driver.execute(
            identifier = null,
            sql = """
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
            parameters = 0,
        )
        driver.execute(null, "CREATE INDEX memories_by_category ON memories(category);", 0)
        driver.execute(
            null,
            "CREATE INDEX memories_by_expires ON memories(expires_at_epoch_ms) " +
                "WHERE expires_at_epoch_ms IS NOT NULL;",
            0,
        )
    }

    private fun insertV1MemoryRow(
        driver: JdbcSqliteDriver,
        id: String,
        text: String,
        category: String,
        conversationId: String? = null,
        createdAt: Long,
        lastAccessed: Long,
        expiresAt: Long? = null,
    ) {
        val conv = conversationId?.let { "'$it'" } ?: "NULL"
        val expires = expiresAt?.toString() ?: "NULL"
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO memories(
                    id, text, category, conversation_id,
                    created_at_epoch_ms, last_accessed_epoch_ms,
                    embedding, expires_at_epoch_ms
                ) VALUES ('$id', '$text', '$category', $conv,
                          $createdAt, $lastAccessed,
                          X'00000000', $expires);
            """.trimIndent(),
            parameters = 0,
        )
    }

    private fun selectAccessCountForId(driver: JdbcSqliteDriver, id: String): Long? {
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT access_count FROM memories WHERE id = '$id';",
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

    private data class V1Fields(
        val text: String,
        val category: String,
        val conversationId: String?,
        val created: Long,
        val lastAccessed: Long,
        val expires: Long?,
    )

    private fun selectV1FieldsForId(driver: JdbcSqliteDriver, id: String): V1Fields {
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT text, category, conversation_id,
                       created_at_epoch_ms, last_accessed_epoch_ms, expires_at_epoch_ms
                FROM memories WHERE id = '$id';
            """.trimIndent(),
            parameters = 0,
            mapper = { cursor ->
                assertTrue("row missing", cursor.next().value)
                QueryResult.Value(
                    V1Fields(
                        text = cursor.getString(0)!!,
                        category = cursor.getString(1)!!,
                        conversationId = cursor.getString(2),
                        created = cursor.getLong(3)!!,
                        lastAccessed = cursor.getLong(4)!!,
                        expires = cursor.getLong(5),
                    ),
                )
            },
        ).value
    }
}
