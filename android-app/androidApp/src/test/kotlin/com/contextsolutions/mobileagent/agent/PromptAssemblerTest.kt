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
    fun `system instruction includes base template and guidelines and no-tools block`() {
        val out = assembler().assembleStructured(userOnly("hi"))

        assertTrue("missing base", "privacy-respecting AI assistant" in out.systemInstruction)
        assertTrue("missing guidelines header", "=== Guidelines ===" in out.systemInstruction)
        // Tool registration is fully disabled — the system instruction now
        // always carries the no-tools block.
        assertTrue("missing tools header", "=== Available tools ===" in out.systemInstruction)
        assertTrue("missing no-callable-tools sentence",
            "You have no callable tools this turn." in out.systemInstruction)
        // No tool schemas are advertised at all.
        assertFalse("legacy schema should NOT appear", "\"name\": \"web_search\"" in out.systemInstruction)
        // No chat-template markers — LiteRT-LM applies them.
        assertFalse("<start_of_turn>" in out.systemInstruction)
        assertFalse("<end_of_turn>" in out.systemInstruction)
    }

    @Test
    fun `tools list is always empty regardless of searchAvailable`() {
        val on = assembler().assembleStructured(userOnly("hi"), searchAvailable = true)
        val off = assembler().assembleStructured(userOnly("hi"), searchAvailable = false)
        assertTrue("tools must be empty when search is on", on.tools.isEmpty())
        assertTrue("tools must be empty when search is off", off.tools.isEmpty())
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
    fun `search context rides on the current user turn, not the system instruction`() {
        val without = assembler().assembleStructured(userOnly("hi"))
        val with = assembler().assembleStructured(
            userOnly("hi"),
            searchContext = "[SEARCH CONTEXT]\nquery: weather toronto\n[/SEARCH CONTEXT]",
        )

        // Never in the system instruction (either case) — it sits at the far
        // front of the context window and loses the recency battle.
        assertFalse("=== Search context for this turn ===" in without.systemInstruction)
        assertFalse("=== Search context for this turn ===" in with.systemInstruction)
        assertFalse("weather toronto" in with.systemInstruction)
        assertFalse("`[SEARCH CONTEXT]` block above" in with.systemInstruction)

        // No block → tail user turn is untouched.
        assertEquals("hi", without.history.last().text)
        assertEquals(HistoryRole.USER, without.history.last().role)

        // With a block → header + payload + pre-flight notice are appended to
        // the current (tail) user message, after its original text.
        val tail = with.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue(tail.text.startsWith("hi"))
        assertTrue("=== Search context for this turn ===" in tail.text)
        assertTrue("weather toronto" in tail.text)
        assertTrue("`[SEARCH CONTEXT]` block above" in tail.text)
    }

    @Test
    fun `search context is appended after a prior refusal turn so fresh evidence wins recency`() {
        // Repro of the disable-then-enable-search bug: turn 1 refused (search
        // off), turn 2 re-asks with search on. The refusal must stay in
        // history (no hygiene) but the evidence must land on the tail user
        // turn — positionally after the refusal, closest to generation.
        val out = assembler().assembleStructured(
            history = listOf(
                ChatMessage.User("did the eagles win last night"),
                ChatMessage.Assistant("I don't have access to real-time data."),
                ChatMessage.User("did the eagles win last night"),
            ),
            searchContext = "[SEARCH CONTEXT]\nquery: did the eagles win\n" +
                "[{\"title\":\"Eagles 28-22 win\"}]\n[/SEARCH CONTEXT]",
        )

        // Refusal turn is preserved unmodified (hygiene intentionally not applied).
        assertEquals(HistoryRole.MODEL, out.history[1].role)
        assertEquals("I don't have access to real-time data.", out.history[1].text)

        // Evidence rides on the tail user turn, after the refusal.
        val tail = out.history.last()
        assertEquals(HistoryRole.USER, tail.role)
        assertTrue(tail.text.startsWith("did the eagles win last night"))
        assertTrue("Eagles 28-22 win" in tail.text)
        assertTrue("`[SEARCH CONTEXT]` block above" in tail.text)
        // System instruction stays clean.
        assertFalse("Eagles 28-22 win" in out.systemInstruction)
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
    fun `searchAvailable false uses the search-off variant of the no-tools block`() {
        val out = assembler().assembleStructured(userOnly("hi"), searchAvailable = false)

        assertFalse("no tool schemas anywhere", "\"name\": \"web_search\"" in out.systemInstruction)
        assertTrue("=== Available tools ===" in out.systemInstruction)
        assertTrue("web search is disabled" in out.systemInstruction)
        assertTrue("enable web search in settings" in out.systemInstruction)
    }

    @Test
    fun `searchAvailable true uses the default no-tools block`() {
        val out = assembler().assembleStructured(userOnly("hi"))
        // Default block mentions the [SEARCH CONTEXT] injection path; the
        // search-off variant doesn't.
        assertTrue("default block must mention SEARCH CONTEXT",
            "`[SEARCH CONTEXT]`" in out.systemInstruction)
        assertFalse("default block must not mention 'web search is disabled'",
            "web search is disabled" in out.systemInstruction)
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
