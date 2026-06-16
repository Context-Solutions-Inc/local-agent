package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [PromptAssembler.renderMemoryBlock] (M5 Phase C / SYSTEM_PROMPT.md §5).
 *
 * Companion to [PromptAssemblerTest], which covers the §3/§4/§7/§8 blocks.
 */
class PromptAssemblerMemoryBlockTest {

    @Test
    fun returns_null_for_empty_list() {
        assertNull(PromptAssembler.renderMemoryBlock(emptyList()))
    }

    @Test
    fun renders_single_memory_with_category_prefix() {
        val rendered = PromptAssembler.renderMemoryBlock(
            listOf(memory("a", MemoryCategory.PREFERENCE, "i love dark roast coffee")),
        )!!
        assertTrue(rendered.contains("=== Relevant context from previous conversations ==="))
        assertTrue("missing bullet line", rendered.contains("- (preference) i love dark roast coffee"))
        // Footer cited verbatim from SYSTEM_PROMPT.md §5.
        assertTrue(rendered.contains("These facts come from previous conversations with this user."))
    }

    @Test
    fun renders_multiple_memories_in_input_order() {
        val rendered = PromptAssembler.renderMemoryBlock(
            listOf(
                memory("a", MemoryCategory.PREFERENCE, "first"),
                memory("b", MemoryCategory.PERSONAL_IDENTITY, "second"),
                memory("c", MemoryCategory.RELATIONSHIP, "third"),
            ),
        )!!
        val firstIdx = rendered.indexOf("first")
        val secondIdx = rendered.indexOf("second")
        val thirdIdx = rendered.indexOf("third")
        assertTrue(firstIdx < secondIdx)
        assertTrue(secondIdx < thirdIdx)
    }

    @Test
    fun caps_at_five_entries_per_systemPromptMd_section5() {
        val memories = (1..8).map { memory("m$it", MemoryCategory.INTEREST, "fact $it") }
        val rendered = PromptAssembler.renderMemoryBlock(memories)!!

        for (i in 1..PromptAssembler.MEMORY_CONTEXT_MAX_ENTRIES) {
            assertTrue("missing fact $i", rendered.contains("fact $i"))
        }
        for (i in (PromptAssembler.MEMORY_CONTEXT_MAX_ENTRIES + 1)..8) {
            assertTrue("fact $i should be dropped (over cap)", !rendered.contains("fact $i"))
        }
    }

    @Test
    fun assembleStructured_includes_block_when_provided() {
        val assembler = PromptAssembler(
            timeContextProvider = { fixedTimeContext() },
        )
        val block = PromptAssembler.renderMemoryBlock(
            listOf(memory("a", MemoryCategory.PREFERENCE, "tea over coffee")),
        )
        val structured = assembler.assembleStructured(
            history = listOf(ChatMessage.User("hello")),
            memoryBlock = block,
        )
        assertTrue(structured.systemInstruction.contains("Relevant context"))
        assertTrue(structured.systemInstruction.contains("(preference) tea over coffee"))
    }

    @Test
    fun assembleStructured_omits_block_when_null() {
        val assembler = PromptAssembler(
            timeContextProvider = { fixedTimeContext() },
        )
        val structured = assembler.assembleStructured(
            history = listOf(ChatMessage.User("hello")),
            memoryBlock = null,
        )
        // Note: the BEHAVIOR_GUIDELINES block references "Relevant context"
        // by name to instruct the model on how to use the §5 block — so the
        // string appears even when the block is omitted. We check the actual
        // header instead.
        assertTrue(
            "memory header should not appear when block is null",
            !structured.systemInstruction.contains(PromptAssembler.MEMORY_CONTEXT_HEADER),
        )
    }

    private fun memory(id: String, category: MemoryCategory, text: String): Memory = Memory(
        id = id,
        text = text,
        category = category,
        conversationId = null,
        createdAtEpochMs = 0L,
        lastAccessedEpochMs = 0L,
        accessCount = 0,
        embedding = FloatArray(Memory.EMBEDDING_DIM) { 0f },
        expiresAtEpochMs = null,
    )

    private fun fixedTimeContext(): TimeContext = TimeContext(
        now = kotlinx.datetime.LocalDateTime(2026, 5, 10, 14, 30),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )
}
