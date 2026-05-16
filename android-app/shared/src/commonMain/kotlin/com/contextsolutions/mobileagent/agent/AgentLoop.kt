package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.classifier.FallThroughReason
import com.contextsolutions.mobileagent.classifier.PreflightDecision
import com.contextsolutions.mobileagent.classifier.PreflightRouter
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.PendingToolCall
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.language.PreferredLanguage
import com.contextsolutions.mobileagent.memory.Memory
import com.contextsolutions.mobileagent.memory.MemoryRetriever
import com.contextsolutions.mobileagent.memory.RememberForgetDetector
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.telemetry.CounterNames
import com.contextsolutions.mobileagent.telemetry.LatencyNames
import com.contextsolutions.mobileagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.mobileagent.telemetry.TelemetryCounters
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The agent layer for a single user turn. The engine drives the multi-step
 * tool-call cycle internally on a single LiteRT-LM Conversation (per the
 * documented pattern at https://ai.google.dev/edge/litert-lm/android), so this
 * class is now a thin coordinator:
 *
 *  1. **Pre-flight (M4 / WS-8)** — call [PreflightRouter] first. If the
 *     classifier emits `FireSearch`, run the search inline, append a
 *     synthetic Assistant(toolCall) + Tool(result) pair to history, and
 *     pass `preflightNotice = true` so the system prompt picks up the
 *     `[PRE-FLIGHT NOTICE BLOCK]` (SYSTEM_PROMPT.md §6). Other decisions
 *     leave the M2 path untouched.
 *  2. Build the system prompt and tool list, hand them to the engine alongside
 *     the user's message.
 *  3. Provide a [ToolDispatcher] callback the engine invokes when the model
 *     emits a tool call. The dispatcher routes to [SearchService], emits UI
 *     events ("Searching: ..."), and enforces the per-turn cap (PRD §3.2.2).
 *  4. Forward streamed text chunks as [AgentEvent.TokenChunk] and finalise
 *     with [AgentEvent.Done] when the engine reports the turn complete.
 *
 * The agent loop no longer parses tool-call markers or runs its own generation
 * pass loop — the engine handles both. Citations, the per-turn cap, and the
 * "use the tool result" prompt block are still ours to manage.
 *
 * **Pre-flight tool calls do NOT count toward [maxToolCalls]** — pre-flight
 * is a "free" search before Gemma sees the turn (M4_PLAN.md §4 Phase D).
 * Gemma's in-loop budget remains the full 3.
 */
class AgentLoop(
    private val session: InferenceSession,
    private val assembler: PromptAssembler,
    private val searchService: SearchService,
    private val preflightRouter: PreflightRouter,
    private val memoryRetriever: MemoryRetriever? = null,
    private val toolHandlers: List<ToolHandler> = emptyList(),
    private val clockIntentDetector: ClockIntentDetector = ClockIntentDetector(),
    private val todoIntentDetector: TodoIntentDetector = TodoIntentDetector(),
    private val todoCommandParser: TodoCommandParser = TodoCommandParser(),
    private val todoResponseFormatter: TodoResponseFormatter = TodoResponseFormatter(),
    private val rememberForgetDetector: RememberForgetDetector = RememberForgetDetector(),
    private val logger: (String) -> Unit = {},
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val responseLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
    private val responseFilter: ResponseFilter = ResponseFilter.NoOp,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {

    private val argumentsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(input: AgentTurnInput): Flow<AgentEvent> = channelFlow {
        // M6 Phase C — counter for the daily_inference event. Increments
        // once per user turn entering the loop (regardless of whether the
        // turn errors later). No content recorded.
        counters.increment(CounterNames.QUERIES_TOTAL)
        logger(
            "[turn] start userMessage=\"${redact(input.userMessage)}\" " +
                "toolHandlers=${toolHandlers.flatMap { it.definitions }.joinToString(",") { it.name }} " +
                "searchAvailable=${searchService.isAvailable()}",
        )

        // Deterministic clock-command short-circuit (PR #11). Runs BEFORE
        // pre-flight and Gemma — when the user message unambiguously asks
        // to set/cancel/list a timer or alarm, we dispatch directly and
        // skip the LLM entirely. This avoids every Gemma reliability
        // failure observed during PR development: number-mangling
        // ("11" -> "1:1"), wrong-tool selection ("timer" chosen for a
        // wall-clock time), `<|"|>` token noise in args, partial-parse
        // field dropping, and minute-rendering glitches in responses.
        //
        // Unmatched messages fall through to the existing LLM path so
        // natural-language phrasings the parser doesn't cover ("wake me
        // up tomorrow morning", compound queries) still work.
        if (toolHandlers.isNotEmpty()) {
            val command = ClockCommandParser.parse(input.userMessage)
            if (command != null) {
                logger("[turn] deterministic clock command: ${command::class.simpleName}")
                runClockCommandDirect(input.userMessage, command)
                return@channelFlow
            }
            // Clock-intent detected but no specific pattern matched: emit a
            // generic guidance message and SKIP the LLM entirely. This is
            // load-bearing — every Gemma failure observed in PR
            // development happened on a clock-intent turn. Falling through
            // to the model just produces garbled time strings ("3:5:5 PM"
            // every day, "7:0:0 AM" etc.). Keeping the LLM out of the
            // clock path is the only way to guarantee a clean response.
            if (clockIntentDetector.isClockIntent(input.userMessage)) {
                logger("[turn] clock intent but unmatched parser; emitting guidance")
                emitClockGuidance(input.userMessage)
                return@channelFlow
            }
            // TODO list shares the same reliability constraint as the clock
            // surface (PR #15): structured CRUD over a typed schema that
            // Gemma cannot be trusted to produce. Clock runs first because
            // its verbs (`set`, `cancel`, `remind`) overlap todo verbs
            // (`set priority`, `remind me to <task>`) — clock wins
            // precedence so the older, more battle-tested path stays
            // authoritative on ambiguous turns. The static guidance below
            // is the explicit no-LLM-fallback contract: an intent-but-no-
            // parse turn returns a fixed reply listing valid command
            // shapes, never falls through to Gemma.
            val todoCommand = todoCommandParser.parse(input.userMessage)
            if (todoCommand != null) {
                logger("[turn] deterministic todo command: ${todoCommand::class.simpleName}")
                runTodoCommandDirect(input.userMessage, todoCommand)
                return@channelFlow
            }
            if (todoIntentDetector.isTodoIntent(input.userMessage)) {
                logger("[turn] todo intent but unmatched parser; emitting guidance")
                emitTodoGuidance(input.userMessage)
                return@channelFlow
            }
        }

        // Explicit memory-command short-circuit. Mirrors the clock/todo
        // pattern above: when the user prefixes a turn with "remember …"
        // or "forget …", dispatch a deterministic acknowledgement and
        // skip the LLM. Without this, Gemma sees "remember" + the
        // add_todo tool description and reliably calls add_todo with
        // the post-prefix payload as the title — wrong tool, plus the
        // model emits no follow-up text and the user gets an empty
        // assistant bubble. The actual memory write still happens
        // downstream in `MemoryExtractor.extract()` (called from
        // ChatViewModel after Done) via the same `RememberForgetDetector`
        // we're consulting here, so we don't duplicate save logic.
        val memoryCommand = rememberForgetDetector.classify(input.userMessage)
        if (memoryCommand !is RememberForgetDetector.Command.None) {
            logger("[turn] explicit memory command: ${memoryCommand::class.simpleName}")
            emitMemoryCommandAck(input.userMessage, memoryCommand)
            return@channelFlow
        }

        // Treat the inbound user message as the trailing turn in history.
        val priorHistory = input.history
        val userMessage = ChatMessage.User(input.userMessage)

        val finalText = StringBuilder()
        val citationsForTurn = mutableListOf<SearchSource>()
        var toolCallsThisTurn = 0
        // Tracks the agent's view of in-progress tool messages so the final
        // turnMessages is faithful to what actually happened.
        val turnAppendix = mutableListOf<ChatMessage>(userMessage)

        // -- Memory retrieval (PRD §3.2.4) --
        // Runs BEFORE pre-flight (M5_PLAN.md §2 — sequential with retrieval
        // first). The retriever is null until M5 wiring lands; the router's
        // empty-list path reproduces M4 behavior exactly.
        val retrievedMemories: List<Memory> = memoryRetriever
            ?.retrieve(input.userMessage)
            ?.map { it.memory }
            ?: emptyList()

        // -- Pre-flight (PRD §3.2.1) --
        // The router runs the classifier, applies thresholds, and (on a
        // high-band hit) rewrites date/time relatives. We branch on its
        // decision before touching the engine: FireSearch injects a synthetic
        // tool round-trip into history; everything else preserves the M2 path.
        //
        // Clock-intent short-circuit (PR #11): the shipped classifier was
        // trained before clock tools existed and occasionally fires a search
        // for queries like "set a 5-minute timer for tea", which derails
        // Gemma into asking for clarification instead of calling set_timer.
        // When the detector matches we skip pre-flight entirely — the
        // classifier's verdict is irrelevant when the user's intent is to
        // invoke a tool the classifier doesn't know about.
        var preflightNotice = false
        val historyForPrompt = mutableListOf<ChatMessage>().apply {
            addAll(priorHistory)
            add(userMessage)
        }
        val skipPreflight = toolHandlers.isNotEmpty() &&
            (clockIntentDetector.isClockIntent(input.userMessage) ||
                todoIntentDetector.isTodoIntent(input.userMessage))
        val decision = if (skipPreflight) {
            logger("[turn] skipping pre-flight (clock intent detected)")
            PreflightDecision.FallThrough(
                reason = FallThroughReason.MiddleBand,
                pSearchRequired = null,
            )
        } else {
            preflightRouter.route(input.userMessage, retrievedMemories)
        }
        when (decision) {
            is PreflightDecision.FireSearch -> {
                send(AgentEvent.SearchStarted(decision.rewrittenQuery))
                val outcome = searchService.search(decision.rewrittenQuery)
                send(AgentEvent.SearchCompleted(outcome))
                val callId = PRE_FLIGHT_CALL_ID
                val argsJson = Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject { put("query", decision.rewrittenQuery) },
                )
                val toolCallMessage = ChatMessage.Assistant(
                    text = "",
                    toolCall = ToolCall(callId, WEB_SEARCH_TOOL_NAME, argsJson),
                )
                val toolResultMessage = when (outcome) {
                    is SearchOutcome.Success -> {
                        citationsForTurn.addAll(outcome.payload.sources)
                        ChatMessage.Tool(
                            callId = callId,
                            toolName = WEB_SEARCH_TOOL_NAME,
                            text = outcome.payload.json,
                            isError = false,
                        )
                    }
                    is SearchOutcome.Error -> ChatMessage.Tool(
                        callId = callId,
                        toolName = WEB_SEARCH_TOOL_NAME,
                        text = "Error: ${outcome.kind.name} — ${outcome.message}",
                        isError = true,
                    )
                }
                turnAppendix.add(toolCallMessage)
                turnAppendix.add(toolResultMessage)
                historyForPrompt.add(toolCallMessage)
                historyForPrompt.add(toolResultMessage)
                preflightNotice = true
            }
            is PreflightDecision.SkipSearch,
            is PreflightDecision.FallThrough,
            is PreflightDecision.SearchDisabled -> Unit // M2 path unchanged
        }

        val structured = assembler.assembleStructured(
            history = historyForPrompt,
            memoryBlock = PromptAssembler.renderMemoryBlock(retrievedMemories),
            preflightNotice = preflightNotice,
            searchAvailable = searchService.isAvailable(),
            responseLanguage = responseLanguage,
            extraTools = toolHandlers.flatMap { it.definitions },
        )
        val request = GenerationRequest(
            systemInstruction = structured.systemInstruction,
            history = structured.history,
            tools = structured.tools,
        )
        logger(
            "[turn] sending to engine systemPromptLen=${structured.systemInstruction?.length ?: 0} " +
                "historyTurns=${structured.history.size} " +
                "toolsRegistered=${structured.tools.joinToString(",") { it.name }}",
        )

        val dispatcher = ToolDispatcher { call ->
            logger(
                "[turn] tool_call name=${call.name} " +
                    "args=\"${redact(call.argumentsJson)}\"",
            )
            handleToolCall(
                call = call,
                turnAppendix = turnAppendix,
                citations = citationsForTurn,
                toolCallsSoFar = toolCallsThisTurn,
            ).also { result ->
                toolCallsThisTurn += 1
                logger("[turn] tool_result name=${call.name} resultPrefix=\"${redact(result)}\"")
            }
        }

        var errored = false
        // M6 Phase C — first-token latency starts when we hand the request
        // to the engine. Pre-flight + memory retrieval + the synthetic
        // search round-trip on FireSearch all happen BEFORE this point;
        // those are observed under their own metrics, so the user-perceived
        // "first text on screen after send()" decomposes cleanly.
        val generateStartMs = nowEpochMs()
        var firstTokenObserved = false
        try {
            session.generate(request, dispatcher).collect { event ->
                when (event) {
                    is GenerationEvent.TokenChunk -> {
                        if (!firstTokenObserved) {
                            counters.observeLatency(
                                LatencyNames.FIRST_TOKEN_MS,
                                nowEpochMs() - generateStartMs,
                            )
                            firstTokenObserved = true
                        }
                        // PR #10 — per-turn ResponseFilter strips disallowed
                        // scripts from streamed tokens. The buffered finalText
                        // therefore holds only allowed characters, so the
                        // ChatMessage.Assistant assembled at line ~204 (which
                        // lands in turnMessages and feeds the next turn's
                        // prompt history) carries no leaked output the model
                        // could re-prime on.
                        val filteredChunk = responseFilter.filter(event.text)
                        if (filteredChunk.isNotEmpty()) {
                            finalText.append(filteredChunk)
                            send(AgentEvent.TokenChunk(filteredChunk))
                        }
                    }
                    is GenerationEvent.Done -> Unit // finalisation happens after collect
                    is GenerationEvent.Error -> {
                        // Engine tool-parse failures occasionally land here
                        // when Gemma emits a malformed structured tool call
                        // (e.g. `<|"|>` quote tokens around array values).
                        // The error message includes the raw `<|tool_call>...`
                        // body — try the marker fallback on it before
                        // surfacing anything to the user. If that recovers a
                        // valid call, we suppress the error entirely and let
                        // the Done path build a normal message.
                        val recovered = if (toolHandlers.isNotEmpty()) {
                            runTextMarkerFallback(event.message, turnAppendix)
                        } else null
                        if (recovered != null) {
                            logger("[turn] recovered from engine error via marker fallback")
                            finalText.clear()
                            finalText.append(recovered)
                        } else {
                            errored = true
                            logger("[turn] engine error, no recovery: ${redact(event.message)}")
                            send(AgentEvent.Error(
                                FRIENDLY_ENGINE_ERROR,
                                event.cause,
                            ))
                        }
                    }
                    is GenerationEvent.FunctionCall -> Unit // legacy path; engine no longer emits these
                }
            }
        } catch (t: Throwable) {
            send(AgentEvent.Error(t.message ?: "engine error", t))
            return@channelFlow
        }

        if (errored) return@channelFlow

        // Marker-fallback: Gemma 4 E2B occasionally emits the tool-call as
        // literal text ("<|tool_call>call:list_alarms<tool_call|>") instead
        // of routing through the structured channel — the engine then
        // surfaces nothing as a structured call (toolCallsThisTurn stays 0).
        // When we detect a text-marker emit and a tool handler claims the
        // name, run the tool ourselves and replace the user-visible text
        // with a deterministically-rendered summary so the user gets a
        // useful response either way. The model's mistake stays invisible.
        if (toolCallsThisTurn == 0 && toolHandlers.isNotEmpty()) {
            val replacement = runTextMarkerFallback(
                rawText = finalText.toString(),
                turnAppendix = turnAppendix,
            )
            if (replacement != null) {
                finalText.clear()
                finalText.append(replacement)
                // No extra TokenChunk emit — the UI clears partialText on
                // Done and renders the final message from finalMessage.text,
                // so replacing finalText alone gives the user the clean
                // string. The raw marker may flicker briefly in the
                // streaming bubble but is gone by the time Done lands.
            }
        }

        // Time-format scrubbing: Gemma intermittently appends extra digits
        // when re-emitting times pulled from the tool result ("7:30 AM"
        // becomes "7:300 AM"). Different minutes trip the bug differently
        // (55 -> 55 fine, 30 -> 300 broken), so we can't preempt it on the
        // tool-output side alone. When any tool handler ran this turn,
        // post-process the response to trim impossible 3+ digit minutes
        // back to the canonical H:MM. Scoped to "tool handler ran this
        // turn" so we don't accidentally munge user prompts that legitimately
        // contain numeric strings (we still skip web_search since that path
        // doesn't go through toolHandlers).
        val anyHandlerToolFired = turnAppendix.any { msg ->
            msg is ChatMessage.Tool && toolHandlers.any { it.handles(msg.toolName) }
        }
        if (anyHandlerToolFired) {
            val cleaned = MINUTE_GLITCH_REGEX.replace(finalText.toString()) { m ->
                "${m.groupValues[1]}:${m.groupValues[2]} ${m.groupValues[3]}"
            }
            if (cleaned != finalText.toString()) {
                logger("[turn] scrubbed minute-glitch in response")
                finalText.clear()
                finalText.append(cleaned)
            }
        }

        val finalMessage = ChatMessage.Assistant(
            text = finalText.toString(),
            citations = citationsForTurn.toList(),
        )
        turnAppendix.add(finalMessage)
        logger(
            "[turn] done finalTextLen=${finalMessage.text.length} " +
                "toolCallsThisTurn=$toolCallsThisTurn " +
                "responsePrefix=\"${redact(finalMessage.text)}\"",
        )
        send(AgentEvent.Done(message = finalMessage, turnMessages = turnAppendix.toList()))
    }

    private fun redact(text: String): String =
        if (text.length <= 80) text else text.take(77) + "..."

    /**
     * Looser tool-call body parser used when [GemmaToolCallBodyParser]
     * (regex-based, scalar values only) returns null. Handles two
     * Gemma-isms observed in the wild:
     *
     *  - `<|"|>` tokens that Gemma sometimes emits in place of literal `"`
     *    around string values
     *  - Array values like `days:[<|"|>mon<|"|>, <|"|>tue<|"|>]` that the
     *    scalar parser rejects
     *
     * Strategy: unescape the quote tokens, wrap bareword keys, then hand
     * the result to kotlinx.serialization's lenient JSON parser. If that
     * succeeds we have a real [ParsedEvent.ToolCall]; otherwise we give up
     * and the caller surfaces the marker as text.
     */
    /**
     * Per-field heuristic extractor. Used when the strict loose parser
     * rejects the body because Gemma sprayed stray `<|"|>` tokens or
     * empty-string entries between array elements (genuinely malformed
     * JSON, observed in production logs). Each known field is pulled
     * independently with its own regex so noise between fields can't
     * cause cascading failures.
     *
     * Covers every clock-tool field name we ship today. Returns null if
     * nothing matched.
     */
    private fun parseHeuristicToolCallBody(body: String): ParsedEvent.ToolCall? {
        val match = LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]

        val args = kotlinx.serialization.json.buildJsonObject {
            fun putInt(key: String, v: Int) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))
            fun putStr(key: String, v: String) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))
            fun putBool(key: String, v: Boolean) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))

            // Numeric scalars — hour/minute, plus the timer parts.
            HOUR_REGEX.find(rawArgs)?.let { putInt("hour", it.groupValues[1].toInt()) }
            MINUTE_REGEX.find(rawArgs)?.let { putInt("minute", it.groupValues[1].toInt()) }
            HOURS_REGEX.find(rawArgs)?.let { putInt("hours", it.groupValues[1].toInt()) }
            MINUTES_REGEX.find(rawArgs)?.let { putInt("minutes", it.groupValues[1].toInt()) }
            SECONDS_REGEX.find(rawArgs)?.let { putInt("seconds", it.groupValues[1].toInt()) }
            // Days: pull `<|"|>DAY<|"|>` patterns inside the array brackets
            // and filter to valid weekday tokens. Stray tokens between
            // elements are skipped because the regex demands a wrapped
            // word.
            DAYS_BLOCK_REGEX.find(rawArgs)?.let { m ->
                val days = DAY_TOKEN_REGEX.findAll(m.groupValues[1])
                    .map { it.groupValues[1].lowercase() }
                    .filter { it in VALID_DAY_TOKENS }
                    .toSet()
                if (days.isNotEmpty()) {
                    put("days", kotlinx.serialization.json.buildJsonArray {
                        for (d in days) add(kotlinx.serialization.json.JsonPrimitive(d))
                    })
                }
            }
            // Label: prefer the wrapped form, fall back to a quoted form.
            (LABEL_WRAPPED_REGEX.find(rawArgs) ?: LABEL_QUOTED_REGEX.find(rawArgs))?.let {
                putStr("label", it.groupValues[1].trim())
            }
            // all (bool) for cancel_* tools.
            ALL_BOOL_REGEX.find(rawArgs)?.let {
                putBool("all", it.groupValues[1].toBooleanStrict())
            }
            // id for cancel_* tools — uuid-style string after id:.
            ID_REGEX.find(rawArgs)?.let {
                putStr("id", it.groupValues[1])
            }
        }
        if (args.isEmpty()) return null
        return ParsedEvent.ToolCall(
            name,
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                args,
            ),
        )
    }

    private fun parseLooseToolCallBody(body: String): ParsedEvent.ToolCall? {
        val match = LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]
        val unescaped = rawArgs.replace(GEMMA_QUOTE_TOKEN, "\"")
        val withQuotedKeys = BAREWORD_KEY_REGEX.replace(unescaped) { m ->
            "\"${m.groupValues[1]}\":"
        }
        val argsJson = "{$withQuotedKeys}"
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            ParsedEvent.ToolCall(name, argsJson)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Scan the model's final text for a Gemma-style tool-call marker
     * (`<|tool_call>call:NAME{args}<tool_call|>`). If we find one that
     * matches a registered handler, dispatch it ourselves and return the
     * formatted summary the caller should show instead of the raw marker.
     *
     * Returns null when no marker is present, when the body can't be
     * parsed, or when no handler claims the name.
     */
    private suspend fun runTextMarkerFallback(
        rawText: String,
        turnAppendix: MutableList<ChatMessage>,
    ): String? {
        val startIdx = rawText.indexOf(TEXT_TOOL_START_MARKER)
        if (startIdx < 0) return null
        val afterStart = rawText.substring(startIdx + TEXT_TOOL_START_MARKER.length)
        val endIdx = afterStart.indexOf(TEXT_TOOL_END_MARKER)
        val body = if (endIdx >= 0) afterStart.substring(0, endIdx) else afterStart
        logger("[turn] marker fallback: raw body=\"${redact(body)}\"")
        // Three-tier parsing (most strict first):
        //
        //   1. parseLooseToolCallBody — unescape <|"|> -> ", quote bareword
        //      keys, validate as JSON. Refuses partial results, so it never
        //      drops fields silently.
        //   2. parseHeuristicToolCallBody — when JSON validation fails
        //      because Gemma sprayed stray <|"|> tokens around array
        //      elements ("days:[<|"|>mon<|"|>,<|"|>,<|"|>tue<|"|>..."),
        //      extract each known field via a targeted regex. Day tokens
        //      come from matching `<|"|>(\w+)<|"|>` inside the brackets
        //      and filtering to the seven valid weekday names. Tolerant
        //      of arbitrary noise between fields.
        //   3. GemmaToolCallBodyParser — last-resort legacy scalar parser.
        //      Does PARTIAL matching, so it can return {hour:11,minute:45}
        //      from a body that also had a malformed days array; the
        //      heuristic tier above catches more, so this only fires on
        //      genuinely-scalar bodies like set_timer.
        val parsed = parseLooseToolCallBody(body.trim())
            ?: parseHeuristicToolCallBody(body.trim())
            ?: GemmaToolCallBodyParser.parse(body.trim())
            ?: run {
                logger("[turn] marker fallback: failed to parse body=\"${redact(body)}\"")
                return null
            }
        val handler = toolHandlers.firstOrNull { it.handles(parsed.name) } ?: run {
            logger("[turn] marker fallback: no handler for ${parsed.name}")
            return null
        }
        logger("[turn] marker fallback: dispatching ${parsed.name} args=\"${redact(parsed.argumentsJson)}\"")
        val callId = "marker-fallback-0"
        turnAppendix.add(
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall(callId, parsed.name, parsed.argumentsJson),
            ),
        )
        val result = handler.execute(PendingToolCall(parsed.name, parsed.argumentsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        turnAppendix.add(
            ChatMessage.Tool(
                callId = callId,
                toolName = parsed.name,
                text = result,
                isError = isError,
            ),
        )
        val rendered = ClockResponseFormatter.format(parsed.name, result)
        logger("[turn] marker fallback: rendered \"${redact(rendered)}\"")
        return rendered
    }


    private suspend fun ProducerScope<AgentEvent>.handleToolCall(
        call: PendingToolCall,
        turnAppendix: MutableList<ChatMessage>,
        citations: MutableList<SearchSource>,
        toolCallsSoFar: Int,
    ): String {
        if (toolCallsSoFar >= maxToolCalls) {
            // Record the rejected call so the conversation history is honest
            // about what happened, and tell the model to stop trying.
            val callId = "call-$toolCallsSoFar"
            turnAppendix.add(ChatMessage.Assistant(text = "", toolCall = ToolCall(callId, call.name, call.argumentsJson)))
            turnAppendix.add(
                ChatMessage.Tool(
                    callId = callId,
                    toolName = call.name,
                    text = TOOL_LIMIT_REACHED_MESSAGE,
                    isError = true,
                ),
            )
            return TOOL_LIMIT_REACHED_MESSAGE
        }

        // Pluggable handlers (clock tools, future additions). The web_search
        // path stays inline below because it emits UI events + accumulates
        // citations — coupling the agent loop owns.
        val handler = toolHandlers.firstOrNull { it.handles(call.name) }
        if (handler != null) {
            val callId = "call-$toolCallsSoFar"
            turnAppendix.add(
                ChatMessage.Assistant(
                    text = "",
                    toolCall = ToolCall(callId, call.name, call.argumentsJson),
                ),
            )
            val result = handler.execute(call)
            val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
            turnAppendix.add(
                ChatMessage.Tool(
                    callId = callId,
                    toolName = call.name,
                    text = result,
                    isError = isError,
                ),
            )
            return result
        }

        if (call.name != WEB_SEARCH_TOOL_NAME) {
            return errorPayload("Unknown tool '${call.name}'")
        }

        val query = extractQuery(call.argumentsJson)
            ?: return errorPayload("web_search arguments did not include a 'query' string")

        val callId = "call-$toolCallsSoFar"
        turnAppendix.add(
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall(callId, call.name, call.argumentsJson),
            ),
        )
        send(AgentEvent.SearchStarted(query))
        val outcome = searchService.search(query)
        send(AgentEvent.SearchCompleted(outcome))

        return when (outcome) {
            is SearchOutcome.Success -> {
                citations.addAll(outcome.payload.sources)
                turnAppendix.add(
                    ChatMessage.Tool(
                        callId = callId,
                        toolName = call.name,
                        text = outcome.payload.json,
                        isError = false,
                    ),
                )
                outcome.payload.json
            }
            is SearchOutcome.Error -> {
                val text = "Error: ${outcome.kind.name} — ${outcome.message}"
                turnAppendix.add(
                    ChatMessage.Tool(
                        callId = callId,
                        toolName = call.name,
                        text = text,
                        isError = true,
                    ),
                )
                text
            }
        }
    }

    /**
     * Deterministic clock path: a typed [ClockCommand] from
     * [ClockCommandParser] becomes a synthetic tool call we dispatch
     * directly via the matching handler, then emit a rendered response
     * straight to the UI. Bypasses pre-flight, the engine, and Gemma
     * entirely — see the comment at the call site in [run].
     *
     * The synthetic User/Assistant(toolCall)/Tool/Assistant(final) chain
     * is appended to `turnMessages` on the Done event so the next turn's
     * history matches what the LLM path would have produced. The next
     * turn can read this history and answer follow-up questions like
     * "did that go through?" naturally.
     */
    private suspend fun ProducerScope<AgentEvent>.runClockCommandDirect(
        userMessageText: String,
        command: ClockCommand,
    ) {
        val (toolName, argsJson) = clockCommandToCall(command)
        val handler = toolHandlers.firstOrNull { it.handles(toolName) }
        if (handler == null) {
            send(AgentEvent.Error(FRIENDLY_ENGINE_ERROR, null))
            return
        }

        val callId = "deterministic-clock-0"
        val userMessage = ChatMessage.User(userMessageText)
        val toolCallMessage = ChatMessage.Assistant(
            text = "",
            toolCall = ToolCall(callId, toolName, argsJson),
        )

        val result = handler.execute(PendingToolCall(toolName, argsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        val toolResultMessage = ChatMessage.Tool(
            callId = callId,
            toolName = toolName,
            text = result,
            isError = isError,
        )

        val rendered = ClockResponseFormatter.format(toolName, result)
        val finalMessage = ChatMessage.Assistant(text = rendered)

        send(AgentEvent.TokenChunk(rendered))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(
                    userMessage,
                    toolCallMessage,
                    toolResultMessage,
                    finalMessage,
                ),
                skipMemoryExtraction = true,
            ),
        )
        logger(
            "[turn] done via deterministic path tool=$toolName " +
                "finalTextLen=${rendered.length}",
        )
    }

    /**
     * Emit a generic "I didn't quite understand that clock command"
     * response and end the turn. Used when [ClockIntentDetector] thinks
     * the message is about timers/alarms but [ClockCommandParser] couldn't
     * pin it down to a specific action. Deliberately does NOT call the
     * LLM — Gemma's clock responses are unreliable enough (number
     * mangling, wrong tool, mis-rendered times) that a static guidance
     * string is strictly better UX than letting it try.
     */
    private suspend fun ProducerScope<AgentEvent>.emitClockGuidance(userMessageText: String) {
        val message = CLOCK_GUIDANCE_TEXT
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * Deterministic TODO path. Synthesises the same User → Assistant(toolCall)
     * → Tool → Assistant(final) chain that [runClockCommandDirect] does so
     * memory extraction and conversation persistence see a complete tool
     * round-trip. Bypasses pre-flight + the engine — TODO is a structured
     * CRUD surface Gemma cannot drive reliably.
     */
    private suspend fun ProducerScope<AgentEvent>.runTodoCommandDirect(
        userMessageText: String,
        command: TodoCommand,
    ) {
        val (toolName, argsJson) = todoCommandToCall(command)
        val handler = toolHandlers.firstOrNull { it.handles(toolName) }
        if (handler == null) {
            send(AgentEvent.Error(FRIENDLY_ENGINE_ERROR, null))
            return
        }

        val callId = "deterministic-todo-0"
        val userMessage = ChatMessage.User(userMessageText)
        val toolCallMessage = ChatMessage.Assistant(
            text = "",
            toolCall = ToolCall(callId, toolName, argsJson),
        )

        val result = handler.execute(PendingToolCall(toolName, argsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        val toolResultMessage = ChatMessage.Tool(
            callId = callId,
            toolName = toolName,
            text = result,
            isError = isError,
        )

        val rendered = todoResponseFormatter.format(toolName, result)
        val finalMessage = ChatMessage.Assistant(text = rendered)

        send(AgentEvent.TokenChunk(rendered))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(
                    userMessage,
                    toolCallMessage,
                    toolResultMessage,
                    finalMessage,
                ),
                skipMemoryExtraction = true,
            ),
        )
        logger(
            "[turn] done via deterministic todo path tool=$toolName " +
                "finalTextLen=${rendered.length}",
        )
    }

    /**
     * Deterministic ack for explicit "remember …" / "forget …" turns.
     * Same pattern as [emitClockGuidance] / [emitTodoGuidance]: short
     * fixed text, no LLM, no tool call. `skipMemoryExtraction = false`
     * is load-bearing — the actual memory write lives in
     * `MemoryExtractor.extract()` downstream and consults the same
     * `RememberForgetDetector` to force-create/delete; we MUST let that
     * run so the user-visible effect (saved/forgotten) actually happens.
     */
    private suspend fun ProducerScope<AgentEvent>.emitMemoryCommandAck(
        userMessageText: String,
        command: RememberForgetDetector.Command,
    ) {
        val message = when (command) {
            is RememberForgetDetector.Command.Remember -> "OK, I'll remember that."
            is RememberForgetDetector.Command.Forget -> "OK, I'll forget that."
            RememberForgetDetector.Command.None -> return
        }
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = false,
            ),
        )
    }

    /**
     * Static guidance message for "intent detected, parser unmatched" TODO
     * turns. Same reliability rationale as [emitClockGuidance] — see the
     * structural comment at the call site in [run] (lines 100–149).
     */
    private suspend fun ProducerScope<AgentEvent>.emitTodoGuidance(userMessageText: String) {
        val message = TODO_GUIDANCE_TEXT
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    private fun todoCommandToCall(command: TodoCommand): Pair<String, String> = when (command) {
        is TodoCommand.Add -> TodoToolHandler.ADD_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("title", JsonPrimitive(command.title))
                command.priority?.let { put("priority", JsonPrimitive(it.name)) }
                command.dueDateEpochMs?.let { put("due_date_epoch_ms", JsonPrimitive(it)) }
            },
        )
        is TodoCommand.List -> TodoToolHandler.LIST_TODOS_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("include_completed", JsonPrimitive(command.includeCompleted))
            },
        )
        is TodoCommand.SetCompleted -> TodoToolHandler.COMPLETE_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("completed", JsonPrimitive(command.completed))
            },
        )
        is TodoCommand.Delete -> TodoToolHandler.DELETE_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
            },
        )
        is TodoCommand.SetPriority -> TodoToolHandler.EDIT_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("priority", JsonPrimitive(command.priority.name))
            },
        )
        is TodoCommand.SetDueDate -> TodoToolHandler.EDIT_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                command.dueDateEpochMs?.let { put("due_date_epoch_ms", JsonPrimitive(it)) }
            },
        )
        is TodoCommand.SetTitle -> TodoToolHandler.EDIT_TODO_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("title", JsonPrimitive(command.title))
            },
        )
        TodoCommand.ClearCompleted -> TodoToolHandler.CLEAR_COMPLETED_TODOS_NAME to "{}"
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRef(ref: TodoRef) {
        when (ref) {
            is TodoRef.Index -> put("index", JsonPrimitive(ref.oneBased))
            is TodoRef.TitleSubstring -> put("title_substring", JsonPrimitive(ref.needle))
        }
    }

    /**
     * Translates a [ClockCommand] into the (toolName, argsJson) pair the
     * [ClockToolHandler] expects. The handler's arg parsing is already
     * tolerant of integer or float JSON numbers, so we emit ints directly.
     */
    private fun clockCommandToCall(command: ClockCommand): Pair<String, String> = when (command) {
        is ClockCommand.SetTimer -> {
            ClockToolHandler.SET_TIMER_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    // Always emit `seconds` — the handler sums hours*3600 +
                    // minutes*60 + seconds, so a single field is enough and
                    // avoids losing precision on the split.
                    put("seconds", JsonPrimitive(command.totalSeconds))
                    if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.SetAlarm -> {
            ClockToolHandler.SET_ALARM_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("hour", JsonPrimitive(command.hour))
                    put("minute", JsonPrimitive(command.minute))
                    if (command.days.isNotEmpty()) {
                        put("days", buildJsonArray {
                            for (d in command.days) add(JsonPrimitive(d.name.lowercase()))
                        })
                    }
                    if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.CancelTimer -> {
            ClockToolHandler.CANCEL_TIMER_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    if (command.all) put("all", JsonPrimitive(true))
                    else if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.CancelAlarm -> {
            ClockToolHandler.CANCEL_ALARM_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    if (command.all) put("all", JsonPrimitive(true))
                    else if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        ClockCommand.ListTimers -> ClockToolHandler.LIST_TIMERS_NAME to "{}"
        ClockCommand.ListAlarms -> ClockToolHandler.LIST_ALARMS_NAME to "{}"
    }

    private fun extractQuery(argsJson: String): String? = try {
        val obj = argumentsJson.parseToJsonElement(argsJson).jsonObject
        (obj["query"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    } catch (_: Throwable) {
        null
    }

    private fun errorPayload(message: String): String = "Error: $message"

    companion object {
        const val DEFAULT_MAX_TOOL_CALLS: Int = 3
        const val WEB_SEARCH_TOOL_NAME: String = "web_search"
        const val PRE_FLIGHT_CALL_ID: String = "preflight-call-0"

        const val TOOL_LIMIT_REACHED_MESSAGE: String =
            "Error: tool call limit reached for this turn. Answer the user with what you have."

        // Gemma 4 LiteRT-LM text-emit fallback format observed in production
        // logs when the structured tool channel misfires:
        //   <|tool_call>call:web_search{query: "..."}<tool_call|>
        const val TEXT_TOOL_START_MARKER: String = "<|tool_call>"
        const val TEXT_TOOL_END_MARKER: String = "<tool_call|>"

        // Catches "H:MMM AM/PM" patterns where the minute has 3+ digits —
        // a small-model rendering glitch where Gemma appends a trailing
        // digit to a correctly-formatted time it pulled from the tool
        // result. Anchored to AM/PM so we don't touch HH:MM:SS-style
        // times or arbitrary numeric strings. Case-insensitive period.
        val MINUTE_GLITCH_REGEX: Regex = Regex(
            """(\d{1,2}):(\d{2})\d+\s*(AM|PM|am|pm|Am|Pm)""",
        )

        // Gemma's text-emit form for a string boundary inside a tool-call
        // body. Each occurrence stands in for a literal `"`; replacing
        // them lets us treat the body as conventional JSON-ish.
        const val GEMMA_QUOTE_TOKEN: String = "<|\"|>"

        // call:NAME{BODY} shape — same as GemmaToolCallBodyParser's call
        // anchor but allowed to be re-used here without exposing internals.
        val LOOSE_CALL_REGEX: Regex = Regex(
            """^\s*call\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*)\}\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // Bareword keys appear as `key:value` at the start of the body or
        // immediately after a `,` or `{`. The lookbehind keeps us from
        // touching keys that already happen to be quoted strings.
        val BAREWORD_KEY_REGEX: Regex = Regex(
            """(?:^|(?<=[\{,]))\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""",
        )

        const val FRIENDLY_ENGINE_ERROR: String =
            "Sorry, I had trouble processing that request. Please try again."

        const val CLOCK_GUIDANCE_TEXT: String =
            "Sorry, I didn't quite understand that clock command. Try " +
                "phrasings like \"set a 5 minute timer\", \"set an alarm for " +
                "7am every weekday\", \"cancel my tea timer\", or \"what " +
                "alarms do I have\"."

        const val TODO_GUIDANCE_TEXT: String =
            "Sorry, I didn't quite understand that todo command. Try " +
                "phrasings like \"add buy milk to my todos\", \"add finish " +
                "report with high priority by tomorrow\", \"list my todos\", " +
                "\"complete #2\", \"delete the gym task\", or \"set #1 to " +
                "high priority\". Due dates accept today, tomorrow, or an " +
                "ISO date like 2026-05-20."

        // Per-field heuristic extractors. Each matches a single
        // `key: value` pair independently so stray tokens between fields
        // can't cause cascading failures. Kept as `val` (not const) since
        // Regex isn't a compile-time constant.
        val HOUR_REGEX: Regex = Regex("""\bhour\s*:\s*(\d+)""")
        val MINUTE_REGEX: Regex = Regex("""\bminute\s*:\s*(\d+)""")
        val HOURS_REGEX: Regex = Regex("""\bhours\s*:\s*(\d+)""")
        val MINUTES_REGEX: Regex = Regex("""\bminutes\s*:\s*(\d+)""")
        val SECONDS_REGEX: Regex = Regex("""\bseconds\s*:\s*(\d+)""")

        val DAYS_BLOCK_REGEX: Regex = Regex("""\bdays\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val DAY_TOKEN_REGEX: Regex = Regex("""<\|"\|>(\w+)<\|"\|>""")
        val VALID_DAY_TOKENS: Set<String> = setOf(
            "mon", "monday",
            "tue", "tuesday", "tues",
            "wed", "wednesday",
            "thu", "thursday", "thurs",
            "fri", "friday",
            "sat", "saturday",
            "sun", "sunday",
        )

        // Label: wrapped form `label:<|"|>X<|"|>` and bare-quoted form
        // `label:"X"`. Both must terminate before a structure char so we
        // don't accidentally grab the rest of the body.
        val LABEL_WRAPPED_REGEX: Regex = Regex("""\blabel\s*:\s*<\|"\|>([^<]+?)<\|"\|>""")
        val LABEL_QUOTED_REGEX: Regex = Regex("""\blabel\s*:\s*"([^"]+)"""")

        val ALL_BOOL_REGEX: Regex = Regex("""\ball\s*:\s*(true|false)""")
        val ID_REGEX: Regex = Regex("""\bid\s*:\s*"?([a-zA-Z0-9_\-]+)"?""")
    }
}
