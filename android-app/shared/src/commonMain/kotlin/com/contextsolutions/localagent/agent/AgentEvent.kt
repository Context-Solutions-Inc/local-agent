package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.search.FormattedSearchPayload
import com.contextsolutions.localagent.search.SearchOutcome

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
     * A "run job …" command (PR #88, invariant #59) resolved to a job and the
     * desktop subprocess is now running. The UI shows a "Running job: <name>…"
     * chip until the LLM's answer (grounded in the job output) starts streaming —
     * jobs can take a while, so this is the in-progress indicator. The existing
     * chat Cancel button (shown the whole turn) kills the run.
     */
    data class JobStarted(val jobName: String) : AgentEvent

    /**
     * Tool execution finished. [outcome] carries either the formatted payload
     * (used to render citation chips) or the typed error (PRD §6.2 — the loop
     * still continues so Gemma can produce a degraded reply).
     */
    data class SearchCompleted(val outcome: SearchOutcome) : AgentEvent

    /**
     * The LLM is about to start streaming tokens. Emitted only on the model
     * inference path — the deterministic short-circuits (weather/finance cards,
     * clock/timer/alarm, my-list, memory-ack, weather-location prompt) return
     * before this point and never fire it. The UI uses it to play the spoken
     * "working on it" acknowledgement in speaker mode, so that cue is suppressed
     * on the fast deterministic renders.
     */
    data object GenerationStarted : AgentEvent

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
        /**
         * Set when this turn should NOT trigger downstream memory
         * extraction — e.g. deterministic clock commands ("what alarms
         * do I have", "set a 5 minute timer"). The user isn't saying
         * anything memorable, just driving a tool. Default false
         * preserves the existing LLM-turn behaviour.
         */
        val skipMemoryExtraction: Boolean = false,
        /**
         * Set by the deterministic WEATHER path (PR #37) when the user named a
         * city + state/province *in this turn's query* that resolved to a
         * catalog location AND no equivalent location memory exists yet. The UI
         * layer hands this to the memory extractor to remember the user's
         * location (deduped), so a later bare "what's the weather?" can reuse
         * it. Holds a natural-language statement like "I live in Miami,
         * Florida". Null on every other turn (and when the location came from
         * an existing memory). Independent of [skipMemoryExtraction], which the
         * weather path still sets true to suppress the *classifier* extractor.
         */
        val locationToRemember: String? = null,
    ) : AgentEvent

    /** Unrecoverable failure (engine error, parser exhaustion, etc.). The loop has stopped. */
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent
}

/** Everything the loop needs to start a new turn: the user's message + the prior conversation. */
data class AgentTurnInput(
    val userMessage: String,
    val history: List<ChatMessage> = emptyList(),
    /**
     * Optional photo (downscaled JPEG) attached to this turn (PR #48). When
     * present the loop skips preflight/search and the deterministic clock/my-list/
     * memory short-circuits, sending the image straight to the model for a
     * vision-grounded answer. Ephemeral: not persisted with the turn.
     */
    val imageBytes: ByteArray? = null,
)

/** Convenience accessor for callers that want the formatted payload off a successful event. */
val AgentEvent.SearchCompleted.payload: FormattedSearchPayload?
    get() = (outcome as? SearchOutcome.Success)?.payload
