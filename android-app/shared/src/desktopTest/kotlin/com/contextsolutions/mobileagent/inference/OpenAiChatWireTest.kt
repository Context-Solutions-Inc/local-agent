package com.contextsolutions.mobileagent.inference

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 * Round-trips the desktop link proxy's wire layer (PR #57): a body built by
 * [buildOllamaChatRequest] (the mobile side) parsed by [parseOpenAiChatRequest]
 * (the desktop proxy) must reconstruct the same [GenerationRequest], and the SSE
 * delta encoder must produce a chunk [parseOllamaStreamChunk] can read back.
 */
@OptIn(ExperimentalEncodingApi::class)
class OpenAiChatWireTest {

    @Test
    fun roundTripsSystemAndHistory() {
        val original = GenerationRequest(
            systemInstruction = "You are helpful.",
            history = listOf(
                HistoryMessage(HistoryRole.USER, "hi"),
                HistoryMessage(HistoryRole.MODEL, "hello"),
                HistoryMessage(HistoryRole.USER, "what is 2+2?"),
            ),
            maxTokens = 321,
        )
        val body = buildOllamaChatRequest(original, "desktop", temperature = 0.7f, keepAlive = "30m")
        val parsed = parseOpenAiChatRequest(body)

        assertEquals("You are helpful.", parsed.systemInstruction)
        assertEquals(3, parsed.history.size)
        assertEquals(HistoryRole.USER, parsed.history[0].role)
        assertEquals("hi", parsed.history[0].text)
        assertEquals(HistoryRole.MODEL, parsed.history[1].role)
        assertEquals("hello", parsed.history[1].text)
        assertEquals("what is 2+2?", parsed.history[2].text)
        assertEquals(321, parsed.maxTokens)
        assertNull(parsed.sampling) // no top_k/top_p sent → default decoding
    }

    @Test
    fun preservesGreedySamplingOverride() {
        val original = GenerationRequest(
            history = listOf(HistoryMessage(HistoryRole.USER, "who won?")),
            sampling = SamplingParams.GREEDY,
        )
        val body = buildOllamaChatRequest(original, "desktop", temperature = 1.0f, keepAlive = "30m")
        val parsed = parseOpenAiChatRequest(body)
        assertTrue(parsed.sampling != null)
        assertEquals(1, parsed.sampling!!.topK)
    }

    @Test
    fun carriesTrailingImageBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val original = GenerationRequest(
            history = listOf(HistoryMessage(HistoryRole.USER, "what's this?", imageBytes = bytes)),
        )
        val body = buildOllamaChatRequest(original, "desktop", temperature = 0.7f, keepAlive = "30m")
        val parsed = parseOpenAiChatRequest(body)
        assertEquals(1, parsed.history.size)
        assertEquals("what's this?", parsed.history[0].text)
        assertContentEquals(bytes, parsed.history[0].imageBytes)
    }

    @Test
    fun sseDeltaEncodesAndParses() {
        val line = openAiDeltaChunk("Paris")
        val (delta, _) = parseOllamaStreamChunk(line)
        assertEquals("Paris", delta)
    }
}
