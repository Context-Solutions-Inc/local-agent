package com.contextsolutions.mobileagent.link

import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.openAiDeltaChunk
import com.contextsolutions.mobileagent.inference.parseOpenAiChatRequest
import com.contextsolutions.mobileagent.link.transport.LinkMethod
import com.contextsolutions.mobileagent.link.transport.LinkRequest
import com.contextsolutions.mobileagent.link.transport.LinkRequestHandler
import com.contextsolutions.mobileagent.link.transport.LinkResponse
import com.contextsolutions.mobileagent.link.transport.LinkStreamEvent
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.sync.SyncBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The desktop half of the mobile↔desktop link, as the ONE implementation of each
 * route body (PR #57 + relay follow-up). Both the LAN Ktor server
 * ([DesktopLinkServer]) and the relay frame dispatcher call this, so the link
 * logic lives in exactly one place; the caller owns authorization (LAN bearer /
 * relay E2EE) and the wire framing (HTTP+SSE / relay frames).
 *
 * Pure commonMain — no platform or SDK types — so it's unit-testable and reusable
 * by the relay dispatcher.
 */
class DesktopLinkRequestHandler(
    private val preferences: DesktopLinkPreferences,
    private val sessionProvider: suspend () -> InferenceSession,
    private val syncService: LinkSyncService,
    // Run a job by id on demand (PR #84). Desktop binds this to the JobService;
    // returns false when the id is unknown. Defaults to a no-op so non-desktop
    // callers and existing tests compile unchanged.
    private val runJob: suspend (String) -> Boolean = { false },
) : LinkRequestHandler {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handleUnary(request: LinkRequest): LinkResponse = when (request.method) {
        LinkMethod.HEALTH ->
            LinkResponse(200, """{"status":"ok","deviceId":"${preferences.config().selfDeviceId}"}""")

        LinkMethod.PAIR -> {
            val mobileId = runCatching {
                json.parseToJsonElement(request.body ?: "{}").jsonObject["deviceId"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty()
            if (mobileId.isNotBlank()) {
                preferences.setConfig(preferences.config().copy(pairedDeviceId = mobileId))
            }
            LinkResponse(200, """{"ok":true,"deviceId":"${preferences.config().selfDeviceId}"}""")
        }

        LinkMethod.SYNC_CHANGES -> {
            val since = request.query["since"]?.toLongOrNull() ?: 0L
            val bundle = syncService.changesSince(since)
            LinkResponse(200, json.encodeToString(SyncBundle.serializer(), bundle))
        }

        LinkMethod.SYNC_UPSERT -> {
            val bundle = json.decodeFromString(SyncBundle.serializer(), request.body ?: "{}")
            syncService.applyFromPeer(bundle)
            LinkResponse(200, """{"ok":true}""")
        }

        LinkMethod.RUN_JOB -> {
            val id = request.query["id"].orEmpty()
            if (id.isNotBlank() && runJob(id)) {
                LinkResponse(200, """{"ok":true}""")
            } else {
                LinkResponse(404, """{"error":"unknown job"}""")
            }
        }

        LinkMethod.CHAT, LinkMethod.SYNC_SUBSCRIBE ->
            LinkResponse(400, """{"error":"${request.method} is a streaming method"}""")
    }

    override fun handleStream(request: LinkRequest): Flow<LinkStreamEvent> = when (request.method) {
        LinkMethod.CHAT -> chatStream(request.body ?: "{}")
        LinkMethod.SYNC_SUBSCRIBE -> subscribeStream()
        else -> flow { emit(LinkStreamEvent.Error(400, "${request.method} is not a streaming method")) }
    }

    /**
     * Drives generation through the desktop's warm model ([sessionProvider]) and
     * maps [GenerationEvent]s to stream items. A generation error is rendered as a
     * single delta chunk then a clean end — matching the prior LAN SSE behaviour,
     * so the phone surfaces it as text rather than a transport failure.
     */
    private fun chatStream(body: String): Flow<LinkStreamEvent> = flow {
        val generationRequest = parseOpenAiChatRequest(body)
        val session = sessionProvider()
        session.generate(generationRequest, null).collect { event ->
            when (event) {
                is GenerationEvent.TokenChunk -> emit(LinkStreamEvent.Data(openAiDeltaChunk(event.text)))
                is GenerationEvent.Done -> emit(LinkStreamEvent.End(200))
                is GenerationEvent.Error -> {
                    emit(LinkStreamEvent.Data(openAiDeltaChunk("[error] ${event.message}")))
                    emit(LinkStreamEvent.End(200))
                }
                is GenerationEvent.FunctionCall -> Unit // not used over the link
            }
        }
    }

    /** Emits "changed" on connect and on every local change, until cancelled. */
    private fun subscribeStream(): Flow<LinkStreamEvent> = flow {
        emit(LinkStreamEvent.Data("changed"))
        syncService.localChanges.collect { emit(LinkStreamEvent.Data("changed")) }
    }
}
