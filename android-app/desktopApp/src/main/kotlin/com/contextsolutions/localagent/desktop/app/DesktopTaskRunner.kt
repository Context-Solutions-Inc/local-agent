package com.contextsolutions.localagent.desktop.app

import com.contextsolutions.localagent.agent.AgentEvent
import com.contextsolutions.localagent.agent.AgentTurnInput
import com.contextsolutions.localagent.agent.ResponseFilter
import com.contextsolutions.localagent.di.AgentLoopFactory
import com.contextsolutions.localagent.language.LanguagePreferences
import com.contextsolutions.localagent.task.QueuedTask
import com.contextsolutions.localagent.task.TaskRunner

/**
 * Production [TaskRunner] for the desktop queue (docs/DESKTOP_PORT_PLAN.md,
 * Phase 7) — runs a [QueuedTask] through the warm [WarmModel] session and the
 * real [com.contextsolutions.localagent.agent.AgentLoop].
 *
 * Each task is one agent turn: build a loop on a fresh session over the resident
 * handle, collect the streamed turn into the final assistant text, and report
 * coarse progress (no token total is known up front, so progress steps at
 * GenerationStarted → first-token → done; the queue pins SUCCEEDED to 1.0). An
 * [AgentEvent.Error] is rethrown so the queue records FAILED with its message.
 * The turn respects the user's [LanguagePreferences] for the system-prompt
 * language directive; the [ResponseFilter] is left as `NoOp` (the streamed text
 * is captured verbatim for the task result).
 */
class DesktopTaskRunner(
    private val warmModel: WarmModel,
    private val factory: AgentLoopFactory,
    private val language: LanguagePreferences,
) : TaskRunner {

    override suspend fun run(task: QueuedTask, onProgress: (Float) -> Unit): String {
        onProgress(0.05f)
        val session = warmModel.session()
        val loop = factory.create(
            session = session,
            responseLanguage = language.preferredLanguage(),
            responseFilter = ResponseFilter.NoOp,
        )

        var finalText: String? = null
        var sawToken = false
        loop.run(AgentTurnInput(userMessage = task.prompt)).collect { event ->
            when (event) {
                is AgentEvent.GenerationStarted -> onProgress(0.1f)
                is AgentEvent.TokenChunk -> {
                    if (!sawToken) {
                        sawToken = true
                        onProgress(0.5f)
                    }
                }
                is AgentEvent.Done -> finalText = event.message.text
                is AgentEvent.Error -> throw RuntimeException(event.message, event.cause)
                else -> Unit
            }
        }
        onProgress(1f)
        // Done always precedes the flow completing on the success path; fall back
        // defensively to empty rather than null so a malformed turn still records.
        return finalText ?: ""
    }
}
