package com.contextsolutions.mobileagent.agent

/**
 * Platform sink for the shared `ChatViewModel`'s diagnostic log lines. Bound
 * per-platform so the logcat tag stays `"ChatViewModel"` on Android (hard
 * invariant #28 — production log tags come from DI), while desktop/headless can
 * route it to stderr. Mirrors [com.contextsolutions.mobileagent.di.AgentLogger].
 */
fun interface ChatLogger {
    fun log(message: String)
}
