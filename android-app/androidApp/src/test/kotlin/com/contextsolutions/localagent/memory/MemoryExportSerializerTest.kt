package com.contextsolutions.localagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExportSerializerTest {

    @Test
    fun round_trips_a_handful_of_memories() {
        val rows = listOf(
            stubMemory(
                id = "m-1",
                text = "i live in toronto",
                category = MemoryCategory.PERSONAL_IDENTITY,
                conversationId = "conv-1",
                createdAt = 1_000L,
                lastAccessed = 2_000L,
                accessCount = 3,
            ),
            stubMemory(
                id = "m-2",
                text = "my favorite team is the Blue Jays",
                category = MemoryCategory.PREFERENCE,
                conversationId = null,
                createdAt = 1_500L,
                lastAccessed = 1_500L,
                accessCount = 0,
            ),
            stubMemory(
                id = "m-3",
                text = "i'm traveling next week",
                category = MemoryCategory.TEMPORARY_CONTEXT,
                conversationId = "conv-2",
                createdAt = 2_000L,
                lastAccessed = 2_500L,
                accessCount = 1,
                expiresAt = 2_700L,
            ),
        )
        val raw = MemoryExportSerializer.encode(
            memories = rows,
            appVersionName = "1.0.0",
            embedderModelVersion = "all-MiniLM-L6-v2-int8-v1.0.0",
            nowEpochMs = 3_000L,
        )

        val decoded = MemoryExportSerializer.decode(raw)
        assertTrue("decode succeeded", decoded is DecodeResult.Ok)
        val export = (decoded as DecodeResult.Ok).export

        assertEquals(MemoryExport.CURRENT_SCHEMA_VERSION, export.schemaVersion)
        assertEquals("1.0.0", export.appVersionName)
        assertEquals(3_000L, export.exportedAtEpochMs)
        assertEquals(3, export.memories.size)

        // Round-trip each entry's mutable fields.
        val rebuilt = export.memories[0]
        assertEquals("i live in toronto", rebuilt.text)
        assertEquals("personal_identity", rebuilt.category)
        assertEquals("conv-1", rebuilt.conversationId)
        assertEquals(1_000L, rebuilt.createdAtEpochMs)
        assertEquals(2_000L, rebuilt.lastAccessedEpochMs)
        assertEquals(3, rebuilt.accessCount)
        assertEquals(null, rebuilt.expiresAtEpochMs)

        assertEquals(2_700L, export.memories[2].expiresAtEpochMs)
        assertEquals(null, export.memories[1].conversationId)
    }

    @Test
    fun empty_memory_list_encodes_and_decodes() {
        val raw = MemoryExportSerializer.encode(
            memories = emptyList(),
            appVersionName = "1.0.0",
            embedderModelVersion = "v1",
            nowEpochMs = 0L,
        )
        val decoded = MemoryExportSerializer.decode(raw) as DecodeResult.Ok
        assertEquals(0, decoded.export.memories.size)
    }

    @Test
    fun rejects_future_schema_version() {
        val raw = """
            {
              "schema_version": ${MemoryExport.CURRENT_SCHEMA_VERSION + 1},
              "app_version_name": "9.9.9",
              "embedder_model_version": "future",
              "exported_at_epoch_ms": 0,
              "memories": []
            }
        """.trimIndent()
        val decoded = MemoryExportSerializer.decode(raw)
        assertTrue(decoded is DecodeResult.Invalid)
        val msg = (decoded as DecodeResult.Invalid).message
        assertTrue(
            "user-friendly schema-mismatch message; got: $msg",
            msg.contains("newer version", ignoreCase = true),
        )
    }

    @Test
    fun rejects_zero_or_negative_schema_version() {
        val raw = """{"schema_version": 0, "app_version_name": "x", "embedder_model_version": "x", "exported_at_epoch_ms": 0, "memories": []}"""
        val decoded = MemoryExportSerializer.decode(raw)
        assertTrue(decoded is DecodeResult.Invalid)
    }

    @Test
    fun rejects_garbage_input_with_friendly_message() {
        val decoded = MemoryExportSerializer.decode("not json at all")
        assertTrue(decoded is DecodeResult.Invalid)
        val msg = (decoded as DecodeResult.Invalid).message
        assertTrue("non-empty error: $msg", msg.isNotBlank())
    }

    @Test
    fun ignores_unknown_top_level_fields() {
        // Forward-compatibility: a future version might add fields we don't
        // know about. The current schema should accept them quietly.
        val raw = """
            {
              "schema_version": ${MemoryExport.CURRENT_SCHEMA_VERSION},
              "app_version_name": "1.0.0",
              "embedder_model_version": "v1",
              "exported_at_epoch_ms": 0,
              "memories": [],
              "extra_future_field": "ignored"
            }
        """.trimIndent()
        val decoded = MemoryExportSerializer.decode(raw)
        assertTrue("forward-compat tolerated", decoded is DecodeResult.Ok)
    }

    private fun stubMemory(
        id: String,
        text: String,
        category: MemoryCategory,
        conversationId: String? = null,
        createdAt: Long = 0L,
        lastAccessed: Long = 0L,
        accessCount: Int = 0,
        expiresAt: Long? = null,
    ): Memory = Memory(
        id = id,
        text = text,
        category = category,
        conversationId = conversationId,
        createdAtEpochMs = createdAt,
        lastAccessedEpochMs = lastAccessed,
        accessCount = accessCount,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { (it % 7) * 0.001f },
        expiresAtEpochMs = expiresAt,
    )
}
