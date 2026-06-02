package com.contextsolutions.mobileagent.di

import com.contextsolutions.mobileagent.agent.AgentLoop
import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.agent.ResponseFilter
import com.contextsolutions.mobileagent.language.PreferredLanguage

/**
 * Builds a per-turn [AgentLoop]. The session lifetime is one user turn; the factory takes
 * the user's [PreferredLanguage] + the matching [ResponseFilter] decided at the call-site so
 * each turn can either enforce the filter (normal turn) or relax it (translation-intent turn).
 * Defaults preserve pre-PR-#10 behaviour for tests and any caller that doesn't yet know about
 * the language path.
 *
 * Lifted into `commonMain` in the desktop port (docs/DESKTOP_PORT_PLAN.md, Phase 3) so both
 * Android and desktop build a real [AgentLoop] through one Koin binding (see [agentCoreModule]).
 */
interface AgentLoopFactory {
    fun create(
        session: InferenceSession,
        responseLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
        responseFilter: ResponseFilter = ResponseFilter.NoOp,
    ): AgentLoop
}

/**
 * Platform sink for the [AgentLoop]'s diagnostic log line. Bound per-platform so the logcat
 * tag stays `"AgentLoop"` on Android (hard invariant #28 — production log tags come from DI),
 * while desktop/headless can route it elsewhere or omit it (the factory no-ops when unbound).
 */
fun interface AgentLogger {
    fun log(message: String)
}
