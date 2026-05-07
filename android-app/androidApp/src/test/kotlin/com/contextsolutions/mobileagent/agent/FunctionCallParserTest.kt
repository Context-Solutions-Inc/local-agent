package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.FinishReason
import com.contextsolutions.mobileagent.inference.GenerationEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionCallParserTest {

    // Existing coverage targets the JSON body variant (`<tool_call>{json}</tool_call>`).
    // Production defaults are exercised by `gemmaProductionFormat()` below.
    private val parser = MarkerFunctionCallParser(
        startMarker = "<tool_call>",
        endMarker = "</tool_call>",
        bodyParser = JsonToolCallBodyParser,
    )

    @Test
    fun `pure text emits text and a terminal Done`() = runTest {
        val events = parser.parse(
            generation("Hello, world.", " That's all."),
        ).toList()

        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("Hello, world. That's all.", text)
        assertTrue(events.last() is ParsedEvent.Done)
    }

    @Test
    fun `complete tool call yields exactly one ToolCall event`() = runTest {
        val events = parser.parse(
            generation("""<tool_call>{"name":"web_search","arguments":{"query":"eagles last game"}}</tool_call>"""),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
        assertTrue("\"query\":\"eagles last game\"" in calls.single().argumentsJson)
    }

    @Test
    fun `text before a tool call is emitted before the call`() = runTest {
        val events = parser.parse(
            generation("Let me check that. ", """<tool_call>{"name":"web_search","arguments":{"query":"eagles"}}</tool_call>"""),
        ).toList()

        val ordered = events.filter { it is ParsedEvent.Text || it is ParsedEvent.ToolCall }
        assertTrue(ordered[0] is ParsedEvent.Text)
        assertTrue(ordered.last() is ParsedEvent.ToolCall)
        assertEquals("Let me check that. ", (ordered[0] as ParsedEvent.Text).chunk)
    }

    @Test
    fun `start marker split across token boundaries is detected`() = runTest {
        val pieces = listOf("hello ", "<tool", "_call>", """{"name":"web_search","arguments":{"query":"x"}}""", "</tool_call>", " bye")
        val events = parser.parse(generation(*pieces.toTypedArray())).toList()

        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("hello  bye", text)

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
    }

    @Test
    fun `end marker split across token boundaries is detected`() = runTest {
        val pieces = listOf(
            """<tool_call>{"name":"web_search","arguments":{"query":"x"}}</tool""",
            "_call>",
        )
        val events = parser.parse(generation(*pieces.toTypedArray())).toList()
        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
    }

    @Test
    fun `multiple tool calls in a single stream`() = runTest {
        val payload = """{"name":"web_search","arguments":{"query":"a"}}"""
        val payload2 = """{"name":"web_search","arguments":{"query":"b"}}"""
        val events = parser.parse(
            generation("<tool_call>$payload</tool_call> middle <tool_call>$payload2</tool_call>"),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(2, calls.size)
        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals(" middle ", text)
    }

    @Test
    fun `malformed JSON inside a marker falls back to raw text`() = runTest {
        val events = parser.parse(
            generation("<tool_call>{this is not json}</tool_call>"),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertTrue(calls.isEmpty())
        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("<tool_call>{this is not json}</tool_call>", text)
    }

    @Test
    fun `unterminated tool call at end of stream falls back to raw text`() = runTest {
        val events = parser.parse(
            generation("<tool_call>{\"name\":\"web_search\""),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertTrue(calls.isEmpty())
        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("<tool_call>{\"name\":\"web_search\"", text)
    }

    @Test
    fun `engine-emitted FunctionCall passes through`() = runTest {
        val flow = flowOf<GenerationEvent>(
            GenerationEvent.FunctionCall(name = "web_search", argumentsJson = """{"query":"x"}"""),
            GenerationEvent.Done(totalTokens = 0, finishReason = FinishReason.FUNCTION_CALL),
        )
        val events = parser.parse(flow).toList()
        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
    }

    @Test
    fun `engine error propagates as parser error`() = runTest {
        val flow = flowOf<GenerationEvent>(
            GenerationEvent.TokenChunk("partial", 0),
            GenerationEvent.Error(message = "boom"),
        )
        val events = parser.parse(flow).toList()
        val errors = events.filterIsInstance<ParsedEvent.Error>()
        assertEquals(1, errors.size)
        assertEquals("boom", errors.single().message)
    }

    @Test
    fun `text emitted before partial-marker is held back across chunks`() = runTest {
        // The parser must not emit "<tool" as a Text chunk because it could be
        // the start of a tool call; verify it waits for the next chunk.
        val pieces = listOf("alpha", "<tool", "_call>{\"name\":\"web_search\",\"arguments\":{}}</tool_call>")
        val events = parser.parse(generation(*pieces.toTypedArray())).toList()

        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("alpha", text)
        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
    }

    @Test
    fun `custom markers can be configured`() = runTest {
        val custom = MarkerFunctionCallParser(
            startMarker = "<<call>>",
            endMarker = "<<end>>",
            bodyParser = JsonToolCallBodyParser,
        )
        val events = custom.parse(
            generation("hi <<call>>{\"name\":\"web_search\",\"arguments\":{\"query\":\"x\"}}<<end>> bye"),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("hi  bye", text)
    }

    @Test
    fun `gemma production defaults parse the on-device emit format`() = runTest {
        // Verified on Pixel 7 + gemma-4-E2B-it-litert-lm 2026-05-07: the model
        // emits `<|tool_call>call:web_search{query: "weather in Toronto right now"}<tool_call|>`.
        val production = MarkerFunctionCallParser() // defaults: Gemma markers + GemmaToolCallBodyParser
        val events = production.parse(
            generation("""<|tool_call>call:web_search{query: "weather in Toronto right now"}<tool_call|>"""),
        ).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
        assertEquals("""{"query":"weather in Toronto right now"}""", calls.single().argumentsJson)
    }

    @Test
    fun `gemma production defaults survive marker splits across chunks`() = runTest {
        val production = MarkerFunctionCallParser()
        val pieces = listOf(
            "Let me check. ",
            "<|tool",
            "_call>call:web_search{query: ",
            "\"weather toronto\"}<tool",
            "_call|>",
            " done.",
        )
        val events = production.parse(generation(*pieces.toTypedArray())).toList()

        val calls = events.filterIsInstance<ParsedEvent.ToolCall>()
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.single().name)
        val text = events.filterIsInstance<ParsedEvent.Text>().joinToString("") { it.chunk }
        assertEquals("Let me check.  done.", text)
    }

    private fun generation(vararg chunks: String) =
        flowOf(
            *chunks.mapIndexed { idx, chunk -> GenerationEvent.TokenChunk(chunk, idx) }.toTypedArray<GenerationEvent>(),
            GenerationEvent.Done(totalTokens = chunks.size, finishReason = FinishReason.END_OF_TURN),
        )
}
