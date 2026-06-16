package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.preferences.RemoteServerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit coverage for the PR #56 Ollama request builder + SSE parser. The live
 * round-trip needs a running Ollama server (operator-validated), so these assert
 * the OpenAI-compatible `/v1/chat/completions` body shape — `model`, a multipart
 * `content` array with an `image_url` data-URI (image FIRST) for the trailing
 * user turn, sampling/stop/keep_alive — and the streaming delta parsing.
 */
class OllamaRequestTest {

    private val json = Json

    private fun req(
        systemInstruction: String? = null,
        history: List<HistoryMessage> = emptyList(),
        maxTokens: Int = 256,
        sampling: SamplingParams? = null,
        stop: List<String> = emptyList(),
    ) = GenerationRequest(
        systemInstruction = systemInstruction,
        history = history,
        maxTokens = maxTokens,
        sampling = sampling,
        stopSequences = stop,
    )

    @Test
    fun carriesModelAndKeepAlive() {
        val out = json.parseToJsonElement(
            buildOllamaChatRequest(
                req(history = listOf(HistoryMessage(HistoryRole.USER, "Hi"))),
                model = "gemma3:4b",
                temperature = 0.7f,
                keepAlive = "30m",
            ),
        ).jsonObject
        assertEquals("gemma3:4b", out["model"]!!.jsonPrimitive.content)
        assertEquals("30m", out["keep_alive"]!!.jsonPrimitive.content)
        assertEquals(true, out["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun omitsKeepAliveForOpenAiServer() {
        // PR #73 — keep_alive is Ollama-specific; a strict OpenAI server may reject it.
        val out = json.parseToJsonElement(
            buildOllamaChatRequest(
                req(history = listOf(HistoryMessage(HistoryRole.USER, "Hi"))),
                model = "gpt-4o-mini",
                temperature = 0.7f,
                keepAlive = "30m",
                serverType = RemoteServerType.OPENAI,
            ),
        ).jsonObject
        assertFalse(out.containsKey("keep_alive"))
        assertEquals(true, out["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun textTurnIsPlainMessages() {
        val out = json.parseToJsonElement(
            buildOllamaChatRequest(
                req(
                    systemInstruction = "You are helpful.",
                    history = listOf(HistoryMessage(HistoryRole.USER, "Hi there")),
                ),
                model = "gemma3:4b",
                temperature = 0.7f,
                keepAlive = "30m",
            ),
        ).jsonObject

        val messages = out["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.isString)
    }

    @Test
    fun imageTurnIsMultipartImageFirst() {
        val out = json.parseToJsonElement(
            buildOllamaChatRequest(
                req(history = listOf(HistoryMessage(HistoryRole.USER, "what is this", imageBytes = byteArrayOf(1, 2, 3)))),
                model = "qwen2.5vl:7b",
                temperature = 0.7f,
                keepAlive = "30m",
            ),
        ).jsonObject

        val content = out["messages"]!!.jsonArray.last().jsonObject["content"]
        assertTrue(content is JsonArray, "image turn content is an array")
        val parts = content.jsonArray
        assertEquals(2, parts.size)
        // Image FIRST (matches Android Contents.of(ImageBytes, Text)).
        assertEquals("image_url", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                .startsWith("data:image/jpeg;base64,"),
        )
        assertEquals("text", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun samplingAndStopSerialised() {
        val out = json.parseToJsonElement(
            buildOllamaChatRequest(
                req(
                    history = listOf(HistoryMessage(HistoryRole.USER, "x")),
                    sampling = SamplingParams.GREEDY,
                    stop = listOf("</s>", "STOP"),
                ),
                model = "gemma3:4b",
                temperature = 1.0f,
                keepAlive = "30m",
            ),
        ).jsonObject
        assertEquals(1, out["top_k"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, out["stop"]!!.jsonArray.size)
    }

    @Test
    fun parsesStreamingContentDelta() {
        val (delta, finish) = parseOllamaStreamChunk("""{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""")
        assertEquals("Hel", delta)
        assertNull(finish)

        val (_, fr) = parseOllamaStreamChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(FinishReason.END_OF_TURN, fr)

        val (_, frLen) = parseOllamaStreamChunk("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals(FinishReason.MAX_TOKENS, frLen)
    }

    @Test
    fun streamParserDegradesGracefully() {
        assertEquals("" to null, parseOllamaStreamChunk("not json"))
        assertEquals("" to null, parseOllamaStreamChunk("""{"usage":{"completion_tokens":5}}"""))
    }
}
