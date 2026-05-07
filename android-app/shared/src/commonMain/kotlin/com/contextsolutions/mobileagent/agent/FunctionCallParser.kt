package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.GenerationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * **Legacy, no longer used in the production path.** Originally written when
 * we believed LiteRT-LM 0.10.2 would surface tool calls only as text markers
 * inside the model's output. In fact, registering tools through
 * `ConversationConfig.tools` (see `LiteRtInferenceEngine`) makes the runtime
 * surface tool calls structurally via `Message.toolCalls`, so this text-marker
 * parser is unused.
 *
 * Kept in the codebase as a fallback for future model swaps that DO emit
 * tool calls as text (and for the existing test coverage). Do not wire it
 * into the agent loop unless the model genuinely emits markers as text.
 */
fun interface FunctionCallParser {
    fun parse(input: Flow<GenerationEvent>): Flow<ParsedEvent>
}

sealed interface ParsedEvent {
    data class Text(val chunk: String) : ParsedEvent
    data class ToolCall(val name: String, val argumentsJson: String) : ParsedEvent
    data class Done(val finishReason: com.contextsolutions.mobileagent.inference.FinishReason) : ParsedEvent
    data class Error(val message: String, val cause: Throwable? = null) : ParsedEvent
}

/**
 * Parses the body that appears between the start and end markers and returns a
 * [ParsedEvent.ToolCall], or null if the body is not parseable for this format
 * (the parser falls back to emitting the raw block as text).
 */
fun interface ToolCallBodyParser {
    fun parse(body: String): ParsedEvent.ToolCall?
}

class MarkerFunctionCallParser(
    private val startMarker: String = DEFAULT_START_MARKER,
    private val endMarker: String = DEFAULT_END_MARKER,
    private val bodyParser: ToolCallBodyParser = GemmaToolCallBodyParser,
) : FunctionCallParser {

    companion object {
        // Verified against gemma-4-E2B-it-litert-lm on Pixel 7 — the model emits
        // `<|tool_call>call:web_search{query: "..."}<tool_call|>` (note pipes
        // inside the angle brackets, NOT JSON inside the markers).
        const val DEFAULT_START_MARKER: String = "<|tool_call>"
        const val DEFAULT_END_MARKER: String = "<tool_call|>"
    }

    override fun parse(input: Flow<GenerationEvent>): Flow<ParsedEvent> = flow {
        val buffer = StringBuilder()
        var insideMarker = false

        suspend fun drainOutsideText() {
            // Inside text mode: scan for the start marker; if not present, emit
            // everything except a possible suffix that could be a marker prefix.
            while (buffer.isNotEmpty()) {
                val startIdx = buffer.indexOfMarker(startMarker)
                if (startIdx >= 0) {
                    if (startIdx > 0) emit(ParsedEvent.Text(buffer.substring(0, startIdx)))
                    buffer.delete(0, startIdx + startMarker.length)
                    insideMarker = true
                    return
                }
                val safeLen = buffer.length - longestPrefixSuffix(buffer, startMarker)
                if (safeLen <= 0) return
                emit(ParsedEvent.Text(buffer.substring(0, safeLen)))
                buffer.delete(0, safeLen)
                return
            }
        }

        suspend fun drainInsideMarker() {
            // Inside tool-call mode: accumulate until we see the end marker.
            val endIdx = buffer.indexOfMarker(endMarker)
            if (endIdx < 0) return
            val payload = buffer.substring(0, endIdx)
            buffer.delete(0, endIdx + endMarker.length)
            insideMarker = false
            val parsed = bodyParser.parse(payload)
            if (parsed != null) {
                emit(parsed)
            } else {
                // Unparseable body — surface the raw block as text so the user
                // sees what the model produced rather than silently dropping it.
                emit(ParsedEvent.Text("$startMarker$payload$endMarker"))
            }
        }

        suspend fun process() {
            // Loop because emitting one event (e.g. closing a marker) may unblock
            // the next chunk in the buffer (e.g. trailing text after the call).
            while (true) {
                val before = buffer.length
                val wasInside = insideMarker
                if (insideMarker) drainInsideMarker() else drainOutsideText()
                if (buffer.length == before && insideMarker == wasInside) return
            }
        }

        var done: GenerationEvent.Done? = null
        var error: GenerationEvent.Error? = null

        run collectLoop@{
            input.collect { event ->
                when (event) {
                    is GenerationEvent.TokenChunk -> {
                        buffer.append(event.text)
                        process()
                    }
                    is GenerationEvent.FunctionCall -> {
                        // Engine already gave us a structured call — pass through.
                        emit(ParsedEvent.ToolCall(event.name, event.argumentsJson))
                    }
                    is GenerationEvent.Done -> {
                        done = event
                        return@collect
                    }
                    is GenerationEvent.Error -> {
                        error = event
                        return@collect
                    }
                }
            }
        }

        // End of stream: drain any residual buffer.
        if (buffer.isNotEmpty()) {
            if (insideMarker) {
                emit(ParsedEvent.Text("$startMarker$buffer"))
            } else {
                emit(ParsedEvent.Text(buffer.toString()))
            }
        }
        when {
            error != null -> emit(ParsedEvent.Error(error!!.message, error!!.cause))
            done != null -> emit(ParsedEvent.Done(done!!.finishReason))
            else -> emit(ParsedEvent.Done(com.contextsolutions.mobileagent.inference.FinishReason.END_OF_TURN))
        }
    }

}

/**
 * Body parser for the Gemma 4 LiteRT-LM emit format:
 * `call:<name>{<key>: <value>, ...}` — bareword keys, double-quoted strings,
 * numeric / boolean / null literals. Translates the body into a normalized
 * JSON arguments string the agent loop can hand to the tool dispatcher.
 *
 * Limitations: nested objects/arrays and single-quoted strings are not
 * supported (Gemma 4 hasn't been observed emitting them for the v1
 * `web_search` schema, which only takes a single `query: "..."`). When the
 * body doesn't match, [parse] returns null and the marker parser falls back
 * to emitting the raw block as text.
 */
object GemmaToolCallBodyParser : ToolCallBodyParser {
    private val callRegex = Regex(
        """^\s*call\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*)\}\s*$""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val argPairRegex = Regex(
        """([A-Za-z_][A-Za-z0-9_]*)\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|true|false|null)""",
    )

    override fun parse(body: String): ParsedEvent.ToolCall? {
        val match = callRegex.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]
        val pairs = argPairRegex.findAll(rawArgs).map { m ->
            // Value group is already JSON-compatible: quoted string, number, or literal.
            "\"${m.groupValues[1]}\":${m.groupValues[2]}"
        }.toList()
        // Reject if there were non-empty args we couldn't parse — the body is
        // probably a different format and we'd rather emit it as text than
        // silently lose the model's intent.
        val nonWhitespace = rawArgs.replace(Regex("[\\s,]"), "")
        val matchedSpan = pairs.sumOf { it.length }
        if (nonWhitespace.isNotEmpty() && matchedSpan == 0) return null
        val argsJson = pairs.joinToString(",", prefix = "{", postfix = "}")
        return ParsedEvent.ToolCall(name = name, argumentsJson = argsJson)
    }
}

/**
 * Body parser for `{"name": ..., "arguments": {...}}` JSON-object tool calls.
 * Not used by the production Gemma 4 path but kept for tests and for any
 * future model swap that emits JSON.
 */
object JsonToolCallBodyParser : ToolCallBodyParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun parse(body: String): ParsedEvent.ToolCall? = try {
        val parsed = json.decodeFromString(RawToolCall.serializer(), body.trim())
        if (parsed.name.isBlank()) null
        else ParsedEvent.ToolCall(parsed.name, parsed.arguments.toString())
    } catch (_: Throwable) {
        null
    }

    @Serializable
    private data class RawToolCall(val name: String, val arguments: JsonElement)
}

/** Returns the index of [marker] in this builder, or -1. */
private fun StringBuilder.indexOfMarker(marker: String): Int = indexOf(marker)

/**
 * Length of the longest non-empty suffix of [buffer] that is also a prefix of
 * [marker], capped at `marker.length - 1` (a full match would have been found
 * by `indexOf` already). Used to know how much of the trailing buffer to hold
 * back in case the marker straddles a token boundary.
 */
internal fun longestPrefixSuffix(buffer: CharSequence, marker: String): Int {
    val max = minOf(buffer.length, marker.length - 1)
    for (len in max downTo 1) {
        var match = true
        for (i in 0 until len) {
            if (buffer[buffer.length - len + i] != marker[i]) {
                match = false
                break
            }
        }
        if (match) return len
    }
    return 0
}
