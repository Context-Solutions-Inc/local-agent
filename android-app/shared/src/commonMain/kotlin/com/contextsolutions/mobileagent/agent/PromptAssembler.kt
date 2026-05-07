package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.HistoryMessage
import com.contextsolutions.mobileagent.inference.HistoryRole
import com.contextsolutions.mobileagent.inference.HistoryToolCall
import com.contextsolutions.mobileagent.inference.ToolDefinition
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime

/**
 * Builds the structured prompt the inference engine consumes.
 *
 * **Why structured (not flat text):** LiteRT-LM 0.10.2 wraps each turn in
 * Gemma's chat template (`<start_of_turn>user…<end_of_turn>`) on its own. If
 * we hand it pre-templated text it gets double-wrapped and the model emits
 * malformed tool-call markers. Instead we hand it a system instruction
 * (text only), a list of role-tagged history messages, and the latest user
 * message, then let LiteRT-LM template them correctly.
 *
 * Phase 1 / M2 covers four of the six system-prompt blocks per
 * `SYSTEM_PROMPT.md`:
 *  - Base template (§3)
 *  - Temporal context (§4, regenerated per turn from device clock + locale)
 *  - Tool definitions (§7) — gated by [searchAvailable]; replaced with the
 *    "no tools available" block when search is off
 *  - Behavior guidelines (§8)
 *
 * Memory (§5) and pre-flight notice (§6) are wired as no-op placeholders.
 */
class PromptAssembler(
    private val timeContextProvider: () -> TimeContext,
    private val toolSchemaJson: String = DEFAULT_WEB_SEARCH_SCHEMA,
) {

    /**
     * Produces the [StructuredPrompt] the engine sends to LiteRT-LM. The last
     * [ChatMessage.User] in [history] is treated as the *current* user message
     * (sent via `sendMessageAsync`); everything before it becomes
     * `initialMessages` on the conversation. Callers must put the user's
     * latest input at the tail of [history].
     */
    fun assembleStructured(
        history: List<ChatMessage>,
        memoryBlock: String? = null,
        preflightNotice: Boolean = false,
        searchAvailable: Boolean = true,
        forceFinalAnswer: Boolean = false,
    ): StructuredPrompt {
        require(history.isNotEmpty()) { "history must not be empty" }

        val systemInstruction = buildSystemInstruction(
            memoryBlock = memoryBlock,
            preflightNotice = preflightNotice,
            searchAvailable = searchAvailable,
            forceFinalAnswer = forceFinalAnswer,
        )

        // The engine inspects the LAST entry in `history` to decide what to
        // send via sendMessageAsync (User text or Tool response); everything
        // else becomes the conversation's initial messages. We hand the full
        // chronological list so that decision can happen in one place.
        val structuredHistory = history.flatMap(::toHistoryMessages)

        // Tool registration is handled by the engine via ConversationConfig.tools
        // — the model only treats tools as callable when they come through that
        // channel. We attach the definition here so the engine can pass it on.
        val tools = if (searchAvailable) listOf(WEB_SEARCH_TOOL_DEFINITION) else emptyList()

        return StructuredPrompt(
            systemInstruction = systemInstruction,
            history = structuredHistory,
            tools = tools,
        )
    }

    private fun buildSystemInstruction(
        memoryBlock: String?,
        preflightNotice: Boolean,
        searchAvailable: Boolean,
        forceFinalAnswer: Boolean,
    ): String = buildString {
        append(BASE_TEMPLATE)
        append("\n\n")
        append(temporalContextBlock(timeContextProvider()))
        if (!memoryBlock.isNullOrBlank()) {
            append("\n\n")
            append(memoryBlock)
        }
        if (preflightNotice) {
            append("\n\n")
            append(PREFLIGHT_NOTICE)
        }
        // Tool schema is registered through ConversationConfig.tools, not
        // injected as text here — the model only treats tools as callable
        // when LiteRT-LM advertises them via its template. We still describe
        // the unavailability case in text so the model knows why no tool is
        // surfaced when search is off.
        if (!searchAvailable) {
            append("\n\n")
            append(NO_TOOLS_BLOCK)
        }
        if (forceFinalAnswer) {
            append("\n\n")
            append(FORCE_FINAL_ANSWER_BLOCK)
        }
        append("\n\n")
        append(BEHAVIOR_GUIDELINES)
    }

    /** Builds the SYSTEM_PROMPT.md §4 temporal block from a pre-resolved [TimeContext]. */
    fun temporalContextBlock(context: TimeContext): String {
        val date = formatIsoDate(context.now)
        val time = formatTime(context.now)
        val day = context.now.dayOfWeek.englishName()
        val tzLabel = context.timeZoneAbbreviation ?: "UTC${context.utcOffset}"
        return """
            === Current date and time ===
            Date: $date ($day)
            Time: $time $tzLabel (${context.timeZoneId}, UTC${context.utcOffset})

            Use this timestamp to interpret relative time expressions in user queries
            (e.g., "yesterday," "last week," "this morning," "last year") and to assess
            the freshness of any search results you receive. Search results from
            significantly before today's date may be outdated.
        """.trimIndent()
    }

    private fun toolDefinitionsBlock(): String = """
        === Available tools ===
        You have access to the following tool. Use it when the user's query
        requires current or specialized information beyond your training data.

        $toolSchemaJson
    """.trimIndent()

    /**
     * Translates a single [ChatMessage] into zero or more [HistoryMessage]s.
     * Assistant turns that emitted a tool call are rendered with the call
     * inlined as Gemma's `<|tool_call>...<tool_call|>` marker so the model
     * sees its own emit format echoed back in history.
     */
    private fun toHistoryMessages(message: ChatMessage): List<HistoryMessage> = when (message) {
        is ChatMessage.System -> listOf(HistoryMessage(HistoryRole.SYSTEM, message.text))
        is ChatMessage.User -> listOf(HistoryMessage(HistoryRole.USER, message.text))
        is ChatMessage.Tool -> listOf(
            HistoryMessage(role = HistoryRole.TOOL, text = message.text, toolName = message.toolName),
        )
        is ChatMessage.Assistant -> {
            val calls = message.toolCall?.let { call ->
                listOf(HistoryToolCall(name = call.name, argumentsJson = call.argumentsJson))
            } ?: emptyList()
            // Skip blank model turns with no calls — they add no signal and
            // some chat templates reject empty assistant content.
            if (message.text.isEmpty() && calls.isEmpty()) {
                emptyList()
            } else {
                listOf(HistoryMessage(HistoryRole.MODEL, message.text, toolCalls = calls))
            }
        }
    }

    companion object {
        val WEB_SEARCH_TOOL_DEFINITION: ToolDefinition = ToolDefinition(
            name = "web_search",
            descriptionJson = DEFAULT_WEB_SEARCH_SCHEMA,
        )

        const val DEFAULT_WEB_SEARCH_SCHEMA: String = """{
  "name": "web_search",
  "description": "Search the web for current information. Use for questions about recent events, current scores, market prices, weather, news, product availability, or any topic where information may have changed recently. Do NOT use for general knowledge, settled history, definitions, or reasoning questions you can answer from training.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "The search query. Should be concise and specific. Resolve relative time expressions (e.g., 'last year' -> '2025') and ambiguous references (e.g., 'my team' -> 'Philadelphia Eagles') to concrete terms before searching."
      }
    },
    "required": ["query"]
  }
}"""

        // Verbatim from SYSTEM_PROMPT.md §3 + §6 + §8 — keep in sync if those sections change.
        const val BASE_TEMPLATE: String = """You are a helpful, accurate, and privacy-respecting AI assistant running
entirely on the user's device. You answer questions, help with tasks, and
have access to a web search tool for retrieving current information when
needed.

You are direct and concise. You match the user's register: casual when they
are casual, precise when they are precise. You do not pad responses with
unnecessary preamble or filler."""

        const val NO_TOOLS_BLOCK: String = """=== Available tools ===
You have no tools available in this turn. Web search is unavailable
(disabled in settings or no API key configured).

Answer the user from your training data. For questions about recent events,
current prices, weather, sports scores, or anything else that may have
changed since training, be explicit that you cannot verify current
information and suggest the user enable web search in settings."""

        const val FORCE_FINAL_ANSWER_BLOCK: String = """=== This turn is final ===
You have already invoked tools the maximum number of times for this turn.
Do not emit another tool call. Answer the user with the information you
already have, citing the search results you've received."""

        const val PREFLIGHT_NOTICE: String = """=== Note on this turn ===
A web search has already been performed on your behalf for this query, and
the results are included below in the conversation as a tool result. Answer
the user's question using those results. Do NOT emit another web_search tool
call for this query unless the results are clearly insufficient and a
different search would help."""

        const val BEHAVIOR_GUIDELINES: String = """=== Guidelines ===

Citation: When you use information from a web search result, briefly
reference the source. Format: include the source domain in parentheses after
the relevant claim, e.g., "The Eagles won 28-22 (espn.com)." Do not invent
URLs or sources you did not receive.

Uncertainty: If you don't know something or aren't sure, say so. Don't
fabricate. For questions about events, people, or facts that may have
changed since your training data, prefer to use web_search rather than guess
- but if search isn't available or fails, give the user your best answer
with an explicit caveat ("As of my training data..." or "I'm not certain
about current details, but...").

Conciseness: Match response length to the question. A simple factual question
gets a one-sentence answer. A complex how-to question gets structured
explanation. Avoid restating the question, avoid filler ("Great question!"),
avoid disclaimers when not relevant.

Memory references: If you're using a fact from the "Relevant context"
section above, you don't need to call attention to it (no need to say "I
remember you mentioned..."). Just use the fact naturally."""

    }
}

/**
 * Result of [PromptAssembler.assembleStructured]. [history] is the full
 * chronological conversation; the engine sends [history] last as the current
 * message via `sendMessageAsync` and uses everything before it as
 * `initialMessages`.
 */
data class StructuredPrompt(
    val systemInstruction: String,
    val history: List<HistoryMessage>,
    val tools: List<ToolDefinition> = emptyList(),
)

/**
 * Pre-resolved temporal facts the assembler renders into the §4 block. Built
 * once per turn from the device clock and locale (see SearchModule's wiring).
 * Tests construct one directly to keep prompt output deterministic.
 */
data class TimeContext(
    val now: LocalDateTime,
    val timeZoneId: String,
    val timeZoneAbbreviation: String?,
    val utcOffset: String,
)

private fun DayOfWeek.englishName(): String = when (this) {
    DayOfWeek.MONDAY -> "Monday"
    DayOfWeek.TUESDAY -> "Tuesday"
    DayOfWeek.WEDNESDAY -> "Wednesday"
    DayOfWeek.THURSDAY -> "Thursday"
    DayOfWeek.FRIDAY -> "Friday"
    DayOfWeek.SATURDAY -> "Saturday"
    DayOfWeek.SUNDAY -> "Sunday"
}

private fun formatIsoDate(dt: LocalDateTime): String =
    "${dt.year.toString().padStart(4, '0')}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"

private fun formatTime(dt: LocalDateTime): String =
    "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
