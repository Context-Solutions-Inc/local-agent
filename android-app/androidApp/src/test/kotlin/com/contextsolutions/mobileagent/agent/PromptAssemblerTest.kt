package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.HistoryRole
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {

    private val fixedContext = TimeContext(
        now = LocalDateTime(2026, 5, 6, 14, 32),
        timeZoneId = "America/Toronto",
        timeZoneAbbreviation = "EDT",
        utcOffset = "-04:00",
    )

    private fun assembler(context: TimeContext = fixedContext) = PromptAssembler(
        timeContextProvider = { context },
    )

    private fun userOnly(message: String) = listOf(ChatMessage.User(message))

    @Test
    fun `temporal block renders date day time and timezone`() {
        val block = assembler().temporalContextBlock(fixedContext)
        assertTrue("Date: 2026-05-06 (Wednesday)" in block)
        assertTrue("Time: 14:32 EDT (America/Toronto, UTC-04:00)" in block)
        assertTrue("=== Current date and time ===" in block)
    }

    @Test
    fun `temporal block falls back to UTC offset when abbreviation is null`() {
        val ctx = fixedContext.copy(timeZoneAbbreviation = null)
        val block = assembler(ctx).temporalContextBlock(ctx)
        assertTrue("Time: 14:32 UTC-04:00 (America/Toronto, UTC-04:00)" in block)
    }

    @Test
    fun `pads single-digit months days hours and minutes to two digits`() {
        val ctx = fixedContext.copy(now = LocalDateTime(2026, 1, 3, 4, 5))
        val block = assembler(ctx).temporalContextBlock(ctx)
        assertTrue("Date: 2026-01-03 (Saturday)" in block)
        assertTrue("Time: 04:05 EDT" in block)
    }

    @Test
    fun `system instruction includes base template and guidelines but not tool schema`() {
        val out = assembler().assembleStructured(userOnly("hi"))

        assertTrue("missing base", "privacy-respecting AI assistant" in out.systemInstruction)
        assertTrue("missing guidelines header", "=== Guidelines ===" in out.systemInstruction)
        // Tool schema is registered through ConversationConfig.tools, not system text.
        assertFalse("schema should NOT be in system text", "\"name\": \"web_search\"" in out.systemInstruction)
        assertFalse("tool header should NOT be in system text when search is available",
            "=== Available tools ===" in out.systemInstruction)
        // No chat-template markers — LiteRT-LM applies them.
        assertFalse("<start_of_turn>" in out.systemInstruction)
        assertFalse("<end_of_turn>" in out.systemInstruction)
    }

    @Test
    fun `tools list contains web_search when search is available`() {
        val out = assembler().assembleStructured(userOnly("hi"), searchAvailable = true)
        assertEquals(1, out.tools.size)
        assertEquals("web_search", out.tools.single().name)
        assertTrue("\"name\": \"web_search\"" in out.tools.single().descriptionJson)
    }

    @Test
    fun `tools list is empty when search is unavailable`() {
        val out = assembler().assembleStructured(userOnly("hi"), searchAvailable = false)
        assertTrue(out.tools.isEmpty())
    }

    @Test
    fun `omits memory block when null or blank`() {
        val withoutMemory = assembler().assembleStructured(userOnly("hi"))
        val withBlank = assembler().assembleStructured(userOnly("hi"), memoryBlock = "   ")

        assertFalse("=== Relevant context" in withoutMemory.systemInstruction)
        assertFalse("=== Relevant context" in withBlank.systemInstruction)
    }

    @Test
    fun `includes memory block when provided`() {
        val out = assembler().assembleStructured(
            history = userOnly("hi"),
            memoryBlock = "=== Relevant context ===\n- User likes the Eagles.",
        )
        assertTrue("=== Relevant context ===" in out.systemInstruction)
        assertTrue("- User likes the Eagles." in out.systemInstruction)
    }

    @Test
    fun `includes pre-flight notice only when flagged`() {
        val without = assembler().assembleStructured(userOnly("hi"))
        val with = assembler().assembleStructured(userOnly("hi"), preflightNotice = true)

        assertFalse("=== Note on this turn ===" in without.systemInstruction)
        assertTrue("=== Note on this turn ===" in with.systemInstruction)
    }

    @Test
    fun `history is rendered fully with the trailing user as the last entry`() {
        val out = assembler().assembleStructured(
            history = listOf(
                ChatMessage.User("hello"),
                ChatMessage.Assistant("hi back"),
                ChatMessage.User("how are you"),
            ),
        )
        assertEquals(3, out.history.size)
        assertEquals(HistoryRole.USER, out.history[0].role)
        assertEquals("hello", out.history[0].text)
        assertEquals(HistoryRole.MODEL, out.history[1].role)
        assertEquals("hi back", out.history[1].text)
        assertEquals(HistoryRole.USER, out.history[2].role)
        assertEquals("how are you", out.history[2].text)
    }

    @Test
    fun `assistant turn that emitted a tool call carries it as a structured toolCall`() {
        val out = assembler().assembleStructured(
            history = listOf(
                ChatMessage.User("eagles?"),
                ChatMessage.Assistant(
                    text = "checking",
                    toolCall = ToolCall("c-0", "web_search", """{"query":"eagles last game"}"""),
                ),
                ChatMessage.Tool("c-0", "web_search", "[{\"title\":\"ESPN\"}]"),
                ChatMessage.User("did they win"),
            ),
        )
        // Model entry: prose preserved as text; tool call surfaced structurally.
        val modelEntry = out.history.first { it.role == HistoryRole.MODEL }
        assertEquals("checking", modelEntry.text)
        assertEquals(1, modelEntry.toolCalls.size)
        assertEquals("web_search", modelEntry.toolCalls.single().name)
        assertEquals("""{"query":"eagles last game"}""", modelEntry.toolCalls.single().argumentsJson)
        // Inline marker text MUST NOT leak into the model turn — the structured
        // form is what Gemma's chat template needs to correlate with the tool response.
        assertFalse("inline marker should not appear in text", "<|tool_call>" in modelEntry.text)
        // Tool result lands as its own role-tagged entry with toolName populated.
        val toolEntry = out.history.first { it.role == HistoryRole.TOOL }
        assertEquals("web_search", toolEntry.toolName)
        assertTrue("ESPN" in toolEntry.text)
    }

    @Test
    fun `historic order is preserved`() {
        val out = assembler().assembleStructured(
            history = listOf(
                ChatMessage.User("first"),
                ChatMessage.Assistant("second"),
                ChatMessage.User("third"),
            ),
        )
        assertEquals("first", out.history[0].text)
        assertEquals("second", out.history[1].text)
        assertEquals("third", out.history[2].text)
    }

    @Test
    fun `searchAvailable false replaces tool definitions with no-tools block`() {
        val out = assembler().assembleStructured(userOnly("hi"), searchAvailable = false)

        assertFalse("schema should not appear when search is unavailable", "\"name\": \"web_search\"" in out.systemInstruction)
        assertTrue("=== Available tools ===" in out.systemInstruction)
        assertTrue("Web search is unavailable" in out.systemInstruction)
        assertTrue("enable web search in settings" in out.systemInstruction)
    }

    @Test
    fun `searchAvailable true does not add the no-tools block`() {
        val out = assembler().assembleStructured(userOnly("hi"))
        assertFalse("Web search is unavailable" in out.systemInstruction)
    }

    @Test
    fun `requires at least one user message`() {
        try {
            assembler().assembleStructured(history = emptyList())
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `stable across calls when context is fixed`() {
        val a = assembler().assembleStructured(userOnly("hello"))
        val b = assembler().assembleStructured(userOnly("hello"))
        assertEquals(a, b)
    }
}
