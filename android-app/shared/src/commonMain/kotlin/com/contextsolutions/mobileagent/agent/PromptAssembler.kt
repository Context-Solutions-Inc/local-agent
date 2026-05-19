package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.HistoryMessage
import com.contextsolutions.mobileagent.inference.HistoryRole
import com.contextsolutions.mobileagent.inference.HistoryToolCall
import com.contextsolutions.mobileagent.inference.ToolDefinition
import com.contextsolutions.mobileagent.language.PreferredLanguage
import com.contextsolutions.mobileagent.memory.Memory
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
 *  - No-tools block (§7) — LLM tool-calling is fully disabled; all tool
 *    dispatch (clock, todo, memory, search) happens BEFORE the model via
 *    regex/parsers (`ClockCommandParser`, `TodoCommandParser`,
 *    `RememberForgetDetector`) or the pre-flight classifier
 *    (`PreflightRouter`). The LLM only consumes prompt + history + an
 *    optional `[SEARCH CONTEXT]` block when pre-flight fired.
 *  - Behavior guidelines (§8)
 */
class PromptAssembler(
    private val timeContextProvider: () -> TimeContext,
) {

    /**
     * Produces the [StructuredPrompt] the engine sends to LiteRT-LM. The last
     * [ChatMessage.User] in [history] is treated as the *current* user message
     * (sent via `sendMessageAsync`); everything before it becomes
     * `initialMessages` on the conversation. Callers must put the user's
     * latest input at the tail of [history].
     *
     * [searchContext] — pre-flight search results rendered as a plain-text
     * block inside the system instruction (under `=== Search context ===`).
     * When non-null, [PREFLIGHT_NOTICE] is also appended so the model knows
     * to use the block.
     */
    fun assembleStructured(
        history: List<ChatMessage>,
        memoryBlock: String? = null,
        searchContext: String? = null,
        searchAvailable: Boolean = true,
        forceFinalAnswer: Boolean = false,
        responseLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
    ): StructuredPrompt {
        require(history.isNotEmpty()) { "history must not be empty" }

        val systemInstruction = buildSystemInstruction(
            memoryBlock = memoryBlock,
            searchContext = searchContext,
            searchAvailable = searchAvailable,
            forceFinalAnswer = forceFinalAnswer,
            responseLanguage = responseLanguage,
        )

        val structuredHistory = history.flatMap(::toHistoryMessages)

        // Tool registration with the LLM is disabled — all tool dispatch
        // happens before the engine, and pre-flight results are injected as
        // a plain-text block in the system instruction (see [searchContext]).
        return StructuredPrompt(
            systemInstruction = systemInstruction,
            history = structuredHistory,
            tools = emptyList(),
        )
    }

    private fun buildSystemInstruction(
        memoryBlock: String?,
        searchContext: String?,
        searchAvailable: Boolean,
        forceFinalAnswer: Boolean,
        responseLanguage: PreferredLanguage,
    ): String = buildString {
        append(BASE_TEMPLATE)
        append("\n\n")
        append(languageDirective(responseLanguage))
        append("\n\n")
        append(temporalContextBlock(timeContextProvider()))
        if (!memoryBlock.isNullOrBlank()) {
            append("\n\n")
            append(memoryBlock)
        }
        if (!searchContext.isNullOrBlank()) {
            append("\n\n")
            append(SEARCH_CONTEXT_HEADER)
            append('\n')
            append(searchContext)
            append("\n\n")
            append(PREFLIGHT_NOTICE)
        }
        // Tool-calling is fully disabled at the LLM layer regardless of
        // [searchAvailable]; the block tells the model not to attempt
        // emitting tool-call markers and how to consume `[SEARCH CONTEXT]`
        // when present. [searchAvailable] still signals whether the user
        // has enabled search in settings — when false, even the pre-flight
        // path can't fire, so search-related questions get the "can't
        // verify" caveat.
        append("\n\n")
        append(if (searchAvailable) NO_TOOLS_BLOCK else NO_TOOLS_SEARCH_OFF_BLOCK)
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
        // Verbatim from SYSTEM_PROMPT.md §3 + §6 + §8 — keep in sync if those sections change.
        // The "respond in {language}" directive used to be appended here as
        // a hard-coded English line (PR `bff6e22`). It's now emitted as a
        // separate block by [languageDirective] so it can be parameterised
        // by the user's Settings preference (PR #10).
        const val BASE_TEMPLATE: String = """You are a helpful, accurate, and privacy-respecting AI assistant running
entirely on the user's device. You answer questions and help with tasks.
When recent information is needed, the host app may pre-fetch it for you and
include it below as a [SEARCH CONTEXT] block; use those results as
authoritative when present.

You are direct and concise. You match the user's register: casual when they
are casual, precise when they are precise. You do not pad responses with
unnecessary preamble or filler."""

        /**
         * Renders the language directive for the system prompt. The user's
         * [PreferredLanguage] selection drives both the displayed name and
         * the [com.contextsolutions.mobileagent.agent.ResponseFilter]
         * allow-list applied to the streamed output. The "unless the user
         * asks for a translation" clause keeps the model free to emit other
         * scripts when explicitly asked — paired with
         * [TranslationIntentDetector] flipping the filter to NoOp for
         * those turns.
         */
        fun languageDirective(language: PreferredLanguage): String {
            val identifier = if (language == PreferredLanguage.EN) {
                "English"
            } else {
                "${language.englishName} (${language.nativeName})"
            }
            return "Respond in $identifier, unless the user explicitly asks " +
                "for a translation or for another language. Avoid mixing scripts " +
                "or inserting characters from other writing systems into the response."
        }

        /**
         * Default "tools disabled" block. Tool-calling is fully disabled on
         * the LLM side; this tells the model not to attempt emitting tool-
         * call markers and explains the `[SEARCH CONTEXT]` injection path
         * the host app uses when fresh data is needed.
         */
        const val NO_TOOLS_BLOCK: String = """=== Available tools ===
You have no callable tools this turn. Do NOT emit tool-call markers like
`<|tool_call>` — the host application strips them and the user will see
broken text. Clock, alarm, todo, and memory commands are handled by the
host BEFORE you see the message; if one reaches you, just answer in plain
text.

When the host has fetched recent information for you, it appears above as a
`[SEARCH CONTEXT]` block. Treat that block as authoritative for current
facts (today's weather, latest scores, current prices, recent news) and
cite the source domains in parentheses (e.g., "5°C, cloudy (weather.gc.ca)").
When no `[SEARCH CONTEXT]` block is present, answer from your training data
and add a brief caveat for anything time-sensitive."""

        /**
         * Variant used when the user has disabled search in settings or
         * provided no API key — pre-flight cannot fire, so the model gets
         * an explicit "search is off" hint.
         */
        const val NO_TOOLS_SEARCH_OFF_BLOCK: String = """=== Available tools ===
You have no callable tools this turn, and web search is disabled in the
user's settings. Do NOT emit tool-call markers like `<|tool_call>`.

Answer from your training data. For questions about recent events, current
prices, weather, sports scores, or anything else that may have changed
since training, be explicit that you cannot verify current information and
suggest the user enable web search in settings."""

        const val FORCE_FINAL_ANSWER_BLOCK: String = """=== This turn is final ===
Answer the user with the information you already have, citing any
`[SEARCH CONTEXT]` source domains you used."""

        const val SEARCH_CONTEXT_HEADER: String = "=== Search context for this turn ==="

        const val PREFLIGHT_NOTICE: String = """The `[SEARCH CONTEXT]` block above contains results the host fetched on
your behalf for THIS query. Use the relevant portions to answer; when the
results group by category (general / news / weather / sports / finance),
use only the groups that match the user's question and ignore the rest.

DO NOT say "I don't have real-time data", "I can't access current
information", or "I don't have weather/sports/finance/news access". The
host has ALREADY fetched the current information for you — it is in the
`[SEARCH CONTEXT]` block. Read it and answer from it. If the block is
present but does not contain the specific fact the user asked for, say so
explicitly (e.g., "the source I have doesn't list tonight's score") rather
than refusing on the grounds of having no real-time data."""

        // SYSTEM_PROMPT.md §5 — verbatim. Wrapped at the same column the file
        // uses so the model sees the exact spec text. The bullet list is
        // rendered separately by [renderMemoryBlock] and inserted at
        // `{memory_list}`.
        const val MEMORY_CONTEXT_HEADER: String = "=== Relevant context from previous conversations ==="

        const val MEMORY_CONTEXT_FOOTER: String = """These facts come from previous conversations with this user. Use them to
personalize your response and resolve ambiguous references (e.g., "my team,"
"my project," "where I live"). Do not mention these facts unprompted unless
they are directly relevant to the current query. If a fact appears outdated
or contradicts what the user says now, prioritize the user's current
statement."""

        /** PRD §5.3 cap on memories per turn. */
        const val MEMORY_CONTEXT_MAX_ENTRIES: Int = 5

        /**
         * Render a [Memory] list into the SYSTEM_PROMPT.md §5 block, or
         * `null` when [memories] is empty (the assembler omits the block
         * entirely so the model isn't primed to invent context).
         *
         * Bullet format (v1, simple): `- (<category>) <text>`. Category
         * prefix tells Gemma what kind of fact this is — useful when v1's
         * verbatim memory text is rough ("user mentioned: i live in
         * toronto" rather than "user lives in toronto"). v1.x replaces
         * the verbatim text with Gemma-generated canonical sentences.
         *
         * Memories are taken in input order — the caller (typically
         * [com.contextsolutions.mobileagent.memory.MemoryRetriever]) sorts
         * by similarity descending. Capped at [MEMORY_CONTEXT_MAX_ENTRIES].
         * Expired `temporary_context` rows MUST be filtered upstream
         * (PRD §5.3 — "filtered out before retrieval and never appear in
         * this block").
         */
        fun renderMemoryBlock(memories: List<Memory>): String? {
            if (memories.isEmpty()) return null
            val rows = memories.take(MEMORY_CONTEXT_MAX_ENTRIES)
            val bullets = rows.joinToString(separator = "\n") { memory ->
                "- (${memory.category.wireName}) ${memory.text}"
            }
            return buildString {
                append(MEMORY_CONTEXT_HEADER)
                append('\n')
                append(bullets)
                append("\n\n")
                append(MEMORY_CONTEXT_FOOTER)
            }
        }

        const val BEHAVIOR_GUIDELINES: String = """=== Guidelines ===

Citation: When you use information from a `[SEARCH CONTEXT]` block, briefly
reference the source. Format: include the source domain in parentheses after
the relevant claim, e.g., "The Eagles won 28-22 (espn.com)." Do not invent
URLs or sources you did not receive.

Uncertainty: If you don't know something or aren't sure, say so. Don't
fabricate. For questions about events, people, or facts that may have
changed since your training data, prefer the `[SEARCH CONTEXT]` block if
present; otherwise give the user your best answer with an explicit caveat
("As of my training data..." or "I'm not certain about current details,
but...").

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
