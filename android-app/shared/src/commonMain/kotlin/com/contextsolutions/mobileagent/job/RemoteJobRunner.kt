package com.contextsolutions.mobileagent.job

import com.contextsolutions.mobileagent.link.transport.LinkMethod
import com.contextsolutions.mobileagent.link.transport.LinkRequest
import com.contextsolutions.mobileagent.link.transport.LinkTransportProvider

/**
 * Mobile-side seam for asking the (authoritative) desktop to run a job now
 * (PR #84). Running a job means spawning an OS subprocess, which only ever
 * happens on the trusted desktop — so the phone can't run anything locally; it
 * sends an imperative `RUN_JOB` request over the link and the desktop executes.
 *
 * Bound only on mobile (desktop uses the local [JobAdmin] path). Online-only:
 * the request needs the relay link UP, mirroring the rest of mobile job control.
 */
interface RemoteJobRunner {
    /** Ask the desktop to run job [id] now. Returns true if the desktop accepted it. */
    suspend fun runNow(id: String): Boolean
}

/**
 * The relay implementation: a single `RUN_JOB` unary over the active
 * [LinkTransportProvider] (the same transport the sync client and chat use). The
 * job id rides in `query["id"]`; the desktop [com.contextsolutions.mobileagent.link.DesktopLinkRequestHandler]
 * maps it to the JobService.
 */
class RelayRemoteJobRunner(private val transports: LinkTransportProvider) : RemoteJobRunner {
    override suspend fun runNow(id: String): Boolean {
        val transport = transports.current() ?: return false
        return transport.unary(
            LinkRequest(LinkMethod.RUN_JOB, query = mapOf("id" to id)),
        ).isSuccess
    }
}
