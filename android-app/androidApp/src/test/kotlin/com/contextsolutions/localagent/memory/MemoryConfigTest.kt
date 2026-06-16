package com.contextsolutions.localagent.memory

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun default_thresholds_match_pr_spec() {
        val d = MemoryConfig.DEFAULT.thresholds
        assertEquals(0.6f, d.ask, 1e-6f)
        assertEquals(0.5f, d.category, 1e-6f)
    }

    @Test
    fun round_trips_through_json() {
        val raw = """
            {
              "model_version": "preflight_memory_shared_v1.0.0",
              "thresholds": { "ask": 0.6, "category": 0.5 }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MemoryConfig.serializer(), raw)
        assertEquals(MemoryConfig.DEFAULT, parsed)
    }

    @Test
    fun ignores_legacy_auto_save_field_in_json() {
        // PR#7 removed the `auto_save` threshold. The shipped JSON parser
        // tolerates an extra key so a stale on-device config doesn't crash
        // app startup after the field is removed.
        val raw = """
            {
              "model_version": "preflight_memory_shared_v1.0.0",
              "thresholds": { "auto_save": 0.85, "ask": 0.6, "category": 0.5 }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MemoryConfig.serializer(), raw)
        assertEquals(MemoryConfig.DEFAULT, parsed)
    }

    @Test
    fun parses_max_memories_when_present() {
        val raw = """
            {
              "model_version": "preflight_memory_shared_v1.0.0",
              "thresholds": { "ask": 0.6, "category": 0.5 },
              "max_memories": 3
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MemoryConfig.serializer(), raw)
        assertEquals(3, parsed.maxMemories)
    }

    @Test
    fun defaults_max_memories_to_100_when_absent() {
        val raw = """
            {
              "model_version": "preflight_memory_shared_v1.0.0",
              "thresholds": { "ask": 0.6, "category": 0.5 }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MemoryConfig.serializer(), raw)
        assertEquals(MemoryConfig.DEFAULT_MAX_MEMORIES, parsed.maxMemories)
    }

    @Test
    fun rejects_non_positive_max_memories() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryConfig.DEFAULT.copy(maxMemories = 0)
        }
    }

    @Test
    fun rejects_out_of_range_thresholds() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(ask = 1.5f, category = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(ask = -0.1f, category = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(ask = 0.15f, category = 1.5f)
        }
    }
}
