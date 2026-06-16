package com.contextsolutions.localagent.sync

import com.contextsolutions.localagent.link.transport.LinkMethod
import com.contextsolutions.localagent.link.transport.LinkRequest
import com.contextsolutions.localagent.link.transport.LinkStreamEvent
import com.contextsolutions.localagent.link.transport.LinkTransportProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Mobile-side client for the desktop link server's sync endpoints (PR #57),
 * routed through the [LinkTransportProvider] so it works over either the LAN
 * HTTP path or the E2EE relay transparently. The `SyncBundle` JSON rides
 * unchanged in the request/response bodies; only the transport swaps.
 *
 * (Formerly `LinkSyncHttpClient` — renamed because the transport may now be the
 * relay, not HTTP.)
 */
class LinkSyncClient(private val transports: LinkTransportProvider) {

    private val json = Json { ignoreUnknownKeys = true }

    /** `/sync/changes?since=` → the peer's change bundle, or null if unreachable. */
    suspend fun fetchChanges(sinceMs: Long): SyncBundle? {
        val transport = transports.current() ?: return null
        val r = transport.unary(
            LinkRequest(LinkMethod.SYNC_CHANGES, query = mapOf("since" to sinceMs.toString())),
        )
        if (!r.isSuccess) return null
        return runCatching { json.decodeFromString(SyncBundle.serializer(), r.body) }.getOrNull()
    }

    /** `/sync/upsert` with a local change bundle. Returns true on success. */
    suspend fun pushChanges(bundle: SyncBundle): Boolean {
        val transport = transports.current() ?: return false
        return transport.unary(
            LinkRequest(LinkMethod.SYNC_UPSERT, body = json.encodeToString(SyncBundle.serializer(), bundle)),
        ).isSuccess
    }

    /**
     * Subscribe to the desktop's change stream. Emits Unit on the initial connect
     * and on every desktop-side change; the collector reconciles on each. The flow
     * completes when the stream ends/errors (the caller restarts it).
     */
    fun subscribe(): Flow<Unit> = flow {
        val transport = transports.current() ?: return@flow
        transport.serverStream(LinkRequest(LinkMethod.SYNC_SUBSCRIBE)).collect { event ->
            if (event is LinkStreamEvent.Data) emit(Unit)
            // End / Error → complete this flow; SyncController's loop retries.
        }
    }
}
