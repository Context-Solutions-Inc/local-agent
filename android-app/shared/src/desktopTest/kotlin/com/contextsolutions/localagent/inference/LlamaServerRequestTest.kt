package com.contextsolutions.localagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit coverage for the PR #55 (Option 3) llama-server request builder + SSE parser.
 * The native round-trip needs a running server (operator on-device), so these assert
 * the `/v1/chat/completions` body shape — a multipart `content` array with an
 * `image_url` data-URI (image FIRST) for the trailing user turn — and the streaming
 * delta parsing that maps to GenerationEvents.
 */
class LlamaServerRequestTest {

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
    fun textTurnIsPlainMessages() {
        val out = json.parseToJsonElement(
            buildChatRequest(
                req(
                    systemInstruction = "You are helpful.",
                    history = listOf(HistoryMessage(HistoryRole.USER, "Hi there")),
                ),
                temperature = 0.7f,
            ),
        ).jsonObject

        val messages = out["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.isString)
        assertEquals(true, out["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(256, out["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun imageTurnIsMultipartImageFirst() {
        val out = json.parseToJsonElement(
            buildChatRequest(
                req(history = listOf(HistoryMessage(HistoryRole.USER, "translate to english", imageBytes = byteArrayOf(1, 2, 3)))),
                temperature = 0.7f,
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
        assertEquals("translate to english", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun samplingAndStopSerialised() {
        val out = json.parseToJsonElement(
            buildChatRequest(
                req(
                    history = listOf(HistoryMessage(HistoryRole.USER, "x")),
                    sampling = SamplingParams.GREEDY,
                    stop = listOf("</s>", "STOP"),
                ),
                temperature = 1.0f,
            ),
        ).jsonObject
        assertEquals(1, out["top_k"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, out["stop"]!!.jsonArray.size)
    }

    @Test
    fun parsesStreamingContentDelta() {
        val (delta, finish) = parseStreamChunk("""{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""")
        assertEquals("Hel", delta)
        assertNull(finish)

        val (_, fr) = parseStreamChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(FinishReason.END_OF_TURN, fr)

        val (_, frLen) = parseStreamChunk("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals(FinishReason.MAX_TOKENS, frLen)
    }

    @Test
    fun streamParserDegradesGracefully() {
        assertEquals("" to null, parseStreamChunk("not json"))
        assertEquals("" to null, parseStreamChunk("""{"usage":{"completion_tokens":5}}"""))
    }

    @Test
    fun gpuVariantSelectionPerHost() {
        // Linux x64: CPU vs Vulkan archives.
        assertEquals("ubuntu-x64", LlamaServerRelease.assetForHost(wantGpu = false, os = "linux", arch = "amd64")!!.label)
        assertEquals("ubuntu-vulkan-x64", LlamaServerRelease.assetForHost(wantGpu = true, os = "linux", arch = "amd64")!!.label)
        // Windows x64.
        assertEquals("win-cpu-x64", LlamaServerRelease.assetForHost(wantGpu = false, os = "windows 11", arch = "amd64")!!.label)
        assertEquals("win-vulkan-x64", LlamaServerRelease.assetForHost(wantGpu = true, os = "windows 11", arch = "amd64")!!.label)
        // macOS: one Metal-capable archive for both (GPU is just -ngl).
        val macCpu = LlamaServerRelease.assetForHost(wantGpu = false, os = "mac os x", arch = "aarch64")!!
        val macGpu = LlamaServerRelease.assetForHost(wantGpu = true, os = "mac os x", arch = "aarch64")!!
        assertEquals("macos-arm64", macCpu.label)
        assertEquals(macCpu.label, macGpu.label)
        // Linux arm64 Vulkan.
        assertEquals("ubuntu-vulkan-arm64", LlamaServerRelease.assetForHost(wantGpu = true, os = "linux", arch = "aarch64")!!.label)
        // Unknown OS → no asset.
        assertNull(LlamaServerRelease.assetForHost(wantGpu = true, os = "solaris", arch = "sparc"))
    }
}
