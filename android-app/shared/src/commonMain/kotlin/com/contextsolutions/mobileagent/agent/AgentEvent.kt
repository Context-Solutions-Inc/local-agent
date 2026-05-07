package com.contextsolutions.mobileagent.agent

import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchOutcome

/**
 * Streaming output of the agent loop. The UI collects this Flow and projects it
 * onto a single [ChatMessage.Assistant] row that grows as tokens stream and
 * search status transitions.
 *
 * The ordering contract: zero or more [TokenChunk] events, optionally
 * interleaved with [SearchStarted]/[SearchCompleted] pairs, terminated by
 * exactly one of [Done] or [Error].
 */
sealed interface AgentEvent {
    /** A run of decoded text from the model. The UI appends to the live assistant message. */
    data class TokenChunk(val text: String) : AgentEvent

    /** The agent has emitted a tool call and is about to execute it. UI shows a "Searching the web..." chip. */
    data class SearchStarted(val query: String) : AgentEvent

    /**
     * Tool execution finished. [outcome] carries either the formatted payload
     * (used to render citation chips) or the typed error (PRD §6.2 — the loop
     * still continues so Gemma can produce a degraded reply).
     */
    data class SearchCompleted(val outcome: SearchOutcome) : AgentEvent

    /**
     * Final assistant message is complete. [message] is the user-visible final
     * turn; [turnMessages] is everything the loop appended during this turn —
     * the [ChatMessage.User] that started it, any intermediate
     * assistant-with-tool-call + [ChatMessage.Tool] pairs, and the final
     * assistant message — in the order Gemma should see them on the next
     * generation. The persistence / history-tracking layer appends
     * [turnMessages] verbatim so the next turn has full conversational context.
     */
    data class Done(
        val message: ChatMessage.Assistant,
        val turnMessages: List<ChatMessage>,
    ) : AgentEvent

    /** Unrecoverable failure (engine error, parser exhaustion, etc.). The loop has stopped. */
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent
}

/** Everything the loop needs to start a new turn: the user's message + the prior conversation. */
data class AgentTurnInput(
    val userMessage: String,
    val history: List<ChatMessage> = emptyList(),
)

/** Convenience accessor for callers that want the formatted payload off a successful event. */
val AgentEvent.SearchCompleted.payload: FormattedSearchPayload?
    get() = (outcome as? SearchOutcome.Success)?.payload
