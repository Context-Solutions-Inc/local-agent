package com.contextsolutions.mobileagent.link

import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.link.transport.LinkMethod
import com.contextsolutions.mobileagent.link.transport.LinkRequest
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.sync.SyncBundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PR #84 — a mobile peer can ask the desktop to run a job over the link. A
 * `RUN_JOB` unary carries the job id in `query["id"]`; the handler invokes the
 * injected `runJob` seam (the desktop JobService in production) and maps the
 * result to 200 (accepted) / 404 (unknown or missing id).
 */
class DesktopLinkRunJobTest {

    @Test
    fun runJobAcceptsKnownIdAndRejectsUnknown() = runBlocking {
        var captured: String? = null
        val handler = DesktopLinkRequestHandler(
            preferences = FakeLinkPrefs(),
            sessionProvider = { error("session not used for RUN_JOB") },
            syncService = NoOpSyncService(),
            runJob = { id -> captured = id; id == "job-1" },
        )

        val ok = handler.handleUnary(LinkRequest(LinkMethod.RUN_JOB, query = mapOf("id" to "job-1")))
        assertEquals(200, ok.status)
        assertEquals("job-1", captured)

        val unknown = handler.handleUnary(LinkRequest(LinkMethod.RUN_JOB, query = mapOf("id" to "nope")))
        assertEquals(404, unknown.status)
        assertEquals("nope", captured)
    }

    @Test
    fun runJobRejectsMissingIdWithoutInvokingSeam() = runBlocking {
        var called: String? = null
        val handler = DesktopLinkRequestHandler(
            preferences = FakeLinkPrefs(),
            sessionProvider = { error("session not used for RUN_JOB") },
            syncService = NoOpSyncService(),
            runJob = { id -> called = id; true },
        )

        val res = handler.handleUnary(LinkRequest(LinkMethod.RUN_JOB))
        assertEquals(404, res.status)
        assertNull(called, "seam must not run on a blank id")
    }

    private class FakeLinkPrefs : DesktopLinkPreferences {
        private val s = MutableStateFlow(DesktopLinkConfig(selfDeviceId = "dev-x"))
        override fun config() = s.value
        override fun configFlow() = s
        override fun setConfig(config: DesktopLinkConfig) { s.value = config }
    }

    private class NoOpSyncService : LinkSyncService {
        override suspend fun changesSince(sinceMs: Long): SyncBundle = SyncBundle()
        override suspend fun applyFromPeer(bundle: SyncBundle) {}
        override val localChanges: SharedFlow<Unit> = MutableSharedFlow()
    }
}
