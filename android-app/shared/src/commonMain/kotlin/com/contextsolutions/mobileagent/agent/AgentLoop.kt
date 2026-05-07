package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.GenerationRequest
import com.contextsolutions.mobileagent.inference.PendingToolCall
import com.contextsolutions.mobileagent.inference.ToolDispatcher
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchService
import com.contextsolutions.mobileagent.search.SearchSource
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The agent layer for a single user turn. The engine drives the multi-step
 * tool-call cycle internally on a single LiteRT-LM Conversation (per the
 * documented pattern at https://ai.google.dev/edge/litert-lm/android), so this
 * class is now a thin coordinator:
 *
 *  1. Build the system prompt and tool list, hand them to the engine alongside
 *     the user's message.
 *  2. Provide a [ToolDispatcher] callback the engine invokes when the model
 *     emits a tool call. The dispatcher routes to [SearchService], emits UI
 *     events ("Searching: ..."), and enforces the per-turn cap (PRD §3.2.2).
 *  3. Forward streamed text chunks as [AgentEvent.TokenChunk] and finalise
 *     with [AgentEvent.Done] when the engine reports the turn complete.
 *
 * The agent loop no longer parses tool-call markers or runs its own generation
 * pass loop — the engine handles both. Citations, the per-turn cap, and the
 * "use the tool result" prompt block are still ours to manage.
 */
class AgentLoop(
    private val session: InferenceSession,
    private val assembler: PromptAssembler,
    private val searchService: SearchService,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
) {

    private val argumentsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(input: AgentTurnInput): Flow<AgentEvent> = channelFlow {
        // Treat the inbound user message as the trailing turn in history.
        val priorHistory = input.history
        val fullHistory = priorHistory + ChatMessage.User(input.userMessage)

        val finalText = StringBuilder()
        val citationsForTurn = mutableListOf<SearchSource>()
        var toolCallsThisTurn = 0
        // Tracks the agent's view of in-progress tool messages so the final
        // turnMessages is faithful to what actually happened.
        val turnAppendix = mutableListOf<ChatMessage>(ChatMessage.User(input.userMessage))

        val structured = assembler.assembleStructured(
            history = fullHistory,
            searchAvailable = searchService.isAvailable(),
        )
        val request = GenerationRequest(
            systemInstruction = structured.systemInstruction,
            history = structured.history,
            tools = structured.tools,
        )

        val dispatcher = ToolDispatcher { call ->
            handleToolCall(
                call = call,
                turnAppendix = turnAppendix,
                citations = citationsForTurn,
                toolCallsSoFar = toolCallsThisTurn,
            ).also { toolCallsThisTurn += 1 }
        }

        var errored = false
        try {
            session.generate(request, dispatcher).collect { event ->
                when (event) {
                    is GenerationEvent.TokenChunk -> {
                        finalText.append(event.text)
                        send(AgentEvent.TokenChunk(event.text))
                    }
                    is GenerationEvent.Done -> Unit // finalisation happens after collect
                    is GenerationEvent.Error -> {
                        errored = true
                        send(AgentEvent.Error(event.message, event.cause))
                    }
                    is GenerationEvent.FunctionCall -> Unit // legacy path; engine no longer emits these
                }
            }
        } catch (t: Throwable) {
            send(AgentEvent.Error(t.message ?: "engine error", t))
            return@channelFlow
        }

        if (errored) return@channelFlow

        val finalMessage = ChatMessage.Assistant(
            text = finalText.toString(),
            citations = citationsForTurn.toList(),
        )
        turnAppendix.add(finalMessage)
        send(AgentEvent.Done(message = finalMessage, turnMessages = turnAppendix.toList()))
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

        const val TOOL_LIMIT_REACHED_MESSAGE: String =
            "Error: tool call limit reached for this turn. Answer the user with what you have."
    }
}
