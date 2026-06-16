package com.contextsolutions.localagent.link

import com.contextsolutions.localagent.agent.InferenceSession
import com.contextsolutions.localagent.job.InlineJobResult
import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkStreamEvent
import com.contextsolutions.localagent.preferences.DesktopLinkConfig
import com.contextsolutions.localagent.preferences.DesktopLinkPreferences
import com.contextsolutions.localagent.sync.LinkSyncService
import com.contextsolutions.localagent.sync.SyncBundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun runJobInlineStreamsOutputForKnownIdAndEnds404ForUnknown() = runBlocking {
        var captured: Pair<String, String>? = null
        val handler = DesktopLinkRequestHandler(
            preferences = FakeLinkPrefs(),
            sessionProvider = { error("session not used for RUN_JOB_INLINE") },
            syncService = NoOpSyncService(),
            runJobInline = { id, keywords ->
                captured = id to keywords
                if (id == "job-1") InlineJobResult.Output("3 listings") else null
            },
        )

        val ok = handler.handleStream(
            LinkRequest(LinkMethod.RUN_JOB_INLINE, query = mapOf("id" to "job-1", "keywords" to "Westport")),
        ).toList()
        assertEquals("job-1" to "Westport", captured)
        val data = ok.filterIsInstance<LinkStreamEvent.Data>().single()
        assertTrue(data.body.contains("3 listings"), "output missing")
        assertTrue(data.body.contains("\"ok\":true"), "ok flag missing")
        assertEquals(200, (ok.last() as LinkStreamEvent.End).status)

        val unknown = handler.handleStream(
            LinkRequest(LinkMethod.RUN_JOB_INLINE, query = mapOf("id" to "nope")),
        ).toList()
        assertEquals(404, (unknown.single() as LinkStreamEvent.End).status)
    }

    @Test
    fun runJobInlineSurfacesFailureBody() = runBlocking {
        val handler = DesktopLinkRequestHandler(
            preferences = FakeLinkPrefs(),
            sessionProvider = { error("session not used") },
            syncService = NoOpSyncService(),
            runJobInline = { _, _ -> InlineJobResult.Failure("exit 1") },
        )

        val events = handler.handleStream(
            LinkRequest(LinkMethod.RUN_JOB_INLINE, query = mapOf("id" to "job-1")),
        ).toList()
        val data = events.filterIsInstance<LinkStreamEvent.Data>().single()
        assertTrue(data.body.contains("\"ok\":false"), "ok flag should be false")
        assertTrue(data.body.contains("exit 1"), "failure detail missing")
        assertEquals(200, (events.last() as LinkStreamEvent.End).status)
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
