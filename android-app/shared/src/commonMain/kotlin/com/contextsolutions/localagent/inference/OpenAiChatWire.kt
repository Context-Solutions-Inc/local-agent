package com.contextsolutions.localagent.inference

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Server-side counterpart of [buildOllamaChatRequest] (PR #57): parses an
 * incoming OpenAI-compatible `POST /v1/chat/completions` body into the engine's
 * [GenerationRequest], and renders streaming deltas back as OpenAI SSE chunks.
 * Lives in commonMain (pure, no platform deps) so the desktop link server's
 * proxy and its round-trip are unit-testable without a real HTTP server.
 *
 * The desktop proxy drives generation through the desktop's own warm model (the
 * [InferenceSession] seam), so `model` in the body is ignored — the warm model
 * decides everything (and may itself be the desktop's local LLM or the desktop's
 * own remote Ollama; the phone never sees that downstream endpoint).
 */
private val WIRE_JSON = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalEncodingApi::class)
fun parseOpenAiChatRequest(jsonBody: String): GenerationRequest {
    val root = WIRE_JSON.parseToJsonElement(jsonBody).jsonObject

    val systemParts = StringBuilder()
    val history = ArrayList<HistoryMessage>()

    root["messages"]?.jsonArray?.forEach { el ->
        val msg = el.jsonObject
        val role = msg["role"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        val (text, imageBytes) = extractContent(msg["content"])
        when (role) {
            "system" -> {
                if (text.isNotBlank()) {
                    if (systemParts.isNotEmpty()) systemParts.append('\n')
                    systemParts.append(text)
                }
            }
            "assistant" -> history += HistoryMessage(role = HistoryRole.MODEL, text = text)
            "tool" -> history += HistoryMessage(role = HistoryRole.TOOL, text = text)
            else -> history += HistoryMessage( // "user" and anything unknown
                role = HistoryRole.USER,
                text = text,
                imageBytes = imageBytes,
            )
        }
    }

    // Fallback matches GenerationRequest.maxTokens' default (kept in sync). The
    // mobile normally sends an explicit max_tokens, so this only applies to a
    // wire body that omits it.
    val maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull ?: 2048
    val temperature = root["temperature"]?.jsonPrimitive?.floatOrNull
    val topK = root["top_k"]?.jsonPrimitive?.intOrNull
    val topP = root["top_p"]?.jsonPrimitive?.floatOrNull
    // Mirror buildOllamaChatRequest: top_k/top_p are sent only on an explicit
    // sampling override (e.g. the search-grounded greedy turn). When present,
    // reconstruct SamplingParams so the desktop reproduces that decoding mode.
    val sampling = if (topK != null && topP != null) {
        SamplingParams(temperature = temperature ?: 1.0f, topK = topK, topP = topP)
    } else {
        null
    }
    val stop = root["stop"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    return GenerationRequest(
        systemInstruction = systemParts.toString().ifBlank { null },
        history = history,
        maxTokens = maxTokens,
        stopSequences = stop,
        sampling = sampling,
    )
}

/**
 * A message `content` is either a plain string or an array of parts
 * (`{type:"text"|"image_url", …}`). Returns the concatenated text and the first
 * inlined image's decoded bytes (data-URI base64), if any.
 */
@OptIn(ExperimentalEncodingApi::class)
private fun extractContent(content: JsonElement?): Pair<String, ByteArray?> {
    if (content == null) return "" to null
    content.jsonPrimitiveOrNull()?.let { return it to null }
    val parts = runCatching { content.jsonArray }.getOrNull() ?: return "" to null
    val text = StringBuilder()
    var image: ByteArray? = null
    for (part in parts) {
        val obj = runCatching { part.jsonObject }.getOrNull() ?: continue
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let {
                if (text.isNotEmpty()) text.append('\n')
                text.append(it)
            }
            "image_url" -> if (image == null) {
                val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                image = url?.let { decodeDataUri(it) }
            }
        }
    }
    return text.toString() to image
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataUri(url: String): ByteArray? {
    // data:image/jpeg;base64,XXXX
    val comma = url.indexOf(',')
    if (comma < 0 || !url.startsWith("data:")) return null
    return runCatching { Base64.Default.decode(url.substring(comma + 1)) }.getOrNull()
}

private fun JsonElement.jsonPrimitiveOrNull() =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** OpenAI SSE delta line body for one streamed text chunk (no `data:` prefix). */
fun openAiDeltaChunk(content: String): String =
    buildJsonObject {
        putJsonArray("choices") {
            add(
                buildJsonObject {
                    put("index", 0)
                    putJsonObject("delta") { put("content", content) }
                    put("finish_reason", JsonNull)
                },
            )
        }
    }.toString()
