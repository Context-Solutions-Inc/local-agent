package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.ToolDefinition

/**
 * A pluggable tool the agent loop can dispatch to. Each handler advertises
 * one or more [ToolDefinition]s (the OpenAPI-style schemas Gemma sees) and
 * handles dispatch for those exact tool names.
 *
 * Web search predates this seam and stays inlined in [AgentLoop] because it
 * also emits UI events (`SearchStarted` / `SearchCompleted`) and accumulates
 * citations — coupling the agent loop owns. New tools that don't need that
 * coupling implement [ToolHandler] and are routed by name.
 */
interface ToolHandler {

    /** Tool schemas to advertise to the model. */
    val definitions: List<ToolDefinition>

    /** True when this handler owns the tool name the model just called. */
    fun handles(toolName: String): Boolean

    /**
     * Execute the call. Return a short result string the engine will feed
     * back to Gemma as the tool response. Errors should return a string
     * starting with "Error:" rather than throwing — the model adapts to
     * error payloads but a thrown exception aborts the turn (PRD §6.2).
     */
    suspend fun execute(call: PendingToolCall): String
}
