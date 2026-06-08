package com.contextsolutions.mobileagent.link

import com.contextsolutions.mobileagent.agent.InferenceSession
import com.contextsolutions.mobileagent.inference.GenerationEvent
import com.contextsolutions.mobileagent.inference.openAiDeltaChunk
import com.contextsolutions.mobileagent.inference.parseOpenAiChatRequest
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.sync.LinkSyncService
import com.contextsolutions.mobileagent.sync.SyncBundle
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The desktop half of the mobile↔desktop link (PR #57): a small Ktor (CIO) HTTP
 * server bound on `0.0.0.0` so a paired phone on the LAN can reach it. Plain HTTP
 * by design (trusted LAN, no SSL); a QR-provisioned bearer token gates every
 * route except `/ping`, so random LAN clients are ignored.
 *
 * Endpoints:
 *  - `GET /ping` — unauthenticated liveness (used to find the bound port).
 *  - `GET /health` — token-gated; reports the desktop device id (the mobile
 *    link monitor + the chat-header dot poll this).
 *  - `POST /v1/chat/completions` — OpenAI-compatible proxy. Drives generation
 *    through [sessionProvider] (the desktop's warm model via the InferenceEngine
 *    seam), so it transparently serves from whatever the desktop is configured
 *    for — its local LLM OR the desktop's own remote Ollama — without ever
 *    exposing that downstream endpoint to the phone.
 *  - `POST /pair` — records the paired mobile's device id; returns the desktop's.
 *  - `GET /sync/changes?since=` / `POST /sync/upsert` — bidirectional LWW sync.
 *  - `GET /sync/subscribe` — SSE the phone holds to learn of desktop-side changes
 *    (then pulls `/sync/changes`); the phone never needs its own server.
 *
 * The proxy is backend-agnostic: it MUST always go through [sessionProvider]
 * (never a hard-coded local target) so the desktop's own backend choice is
 * honored end-to-end.
 */
class DesktopLinkServer(
    private val preferences: DesktopLinkPreferences,
    private val sessionProvider: suspend () -> InferenceSession,
    private val syncService: LinkSyncService,
    /** Updated as phones connect/disconnect (their held `/sync/subscribe` SSE). */
    private val connectionStatus: MutableDesktopLinkConnectionStatus? = null,
    /**
     * PR #74 — handles the Stripe Checkout success redirect (`GET
     * /subscribe/callback?claim_code=…`). The browser is redirected here by the
     * gateway after payment; the handler exchanges the one-time code for the
     * account credential. Unauthenticated (the browser has no bearer); loopback
     * binding + a single-use code are the controls. Returns true on success.
     */
    private val onSubscribeCallback: (suspend (claimCode: String) -> Boolean)? = null,
    private val preferredPort: Int = DesktopLinkConfig.DEFAULT_PORT,
    private val logger: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Number of phones currently holding a /sync/subscribe stream. The mobile keeps
    // it open while foregrounded + linked, so >0 means "a phone is connected".
    private val activeSubscribers = java.util.concurrent.atomic.AtomicInteger(0)

    private fun subscriberOpened() {
        activeSubscribers.incrementAndGet()
        connectionStatus?.set(true)
    }

    private fun subscriberClosed() {
        if (activeSubscribers.decrementAndGet() <= 0) connectionStatus?.set(false)
    }

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var boundPort: Int = 0
        private set

    /** Ensure a pairing token exists (minted once), start the server, return the bound port. */
    @OptIn(ExperimentalUuidApi::class)
    fun start(): Int {
        ensurePairingToken()
        val port = runCatching { startOn(preferredPort) }.getOrElse {
            logger("port $preferredPort unavailable (${it.message}); using an ephemeral port")
            startOn(0)
        }
        boundPort = port
        logger("listening on 0.0.0.0:$port")
        return port
    }

    fun stop() {
        runCatching { server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000) }
        server = null
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun ensurePairingToken() {
        val cfg = preferences.config()
        if (cfg.pairingToken.isBlank()) {
            preferences.setConfig(cfg.copy(pairingToken = Uuid.random().toString()))
        }
    }

    private fun startOn(port: Int): Int {
        val s = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger("route error: ${cause.message}")
                    call.respondText(
                        """{"error":${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(cause.message ?: "error"))}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                }
            }
            routing {
                get("/ping") { call.respondText("ok") }

                // PR #74 — Stripe Checkout success redirect. Browser-facing (no
                // bearer); the gateway only ever redirects a one-time claim_code
                // to this loopback address. Hands the code to the subscription
                // service, then shows a "return to the app" page.
                get("/subscribe/callback") {
                    val code = call.request.queryParameters["claim_code"].orEmpty()
                    val canceled = call.request.queryParameters["status"] == "canceled"
                    val ok = !canceled && code.isNotBlank() &&
                        (onSubscribeCallback?.invoke(code) ?: false)
                    val message = when {
                        canceled -> "Checkout canceled. You can close this tab."
                        ok -> "Subscription activated. You can return to the Mobile Agent app."
                        else -> "Could not finish activation. Return to the app and try again."
                    }
                    call.respondText(
                        "<!doctype html><meta charset=utf-8><title>Mobile Agent</title>" +
                            "<body style=\"font-family:sans-serif;text-align:center;margin-top:4em\">" +
                            "<h2>$message</h2></body>",
                        ContentType.Text.Html,
                    )
                }

                get("/health") {
                    if (!call.authorized()) return@get call.unauthorized()
                    call.respondText(
                        """{"status":"ok","deviceId":"${preferences.config().selfDeviceId}"}""",
                        ContentType.Application.Json,
                    )
                }

                post("/v1/chat/completions") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val body = call.receiveText()
                    val request = parseOpenAiChatRequest(body)
                    val session = sessionProvider()
                    call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                        session.generate(request, null).collect { event ->
                            when (event) {
                                is GenerationEvent.TokenChunk -> {
                                    write("data: ${openAiDeltaChunk(event.text)}\n\n")
                                    flush()
                                }
                                is GenerationEvent.Done -> {
                                    write("data: [DONE]\n\n")
                                    flush()
                                }
                                is GenerationEvent.Error -> {
                                    write("data: ${openAiDeltaChunk("[error] ${event.message}")}\n\n")
                                    write("data: [DONE]\n\n")
                                    flush()
                                }
                                is GenerationEvent.FunctionCall -> Unit // not used over the link
                            }
                        }
                    }
                }

                post("/pair") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val mobileId = runCatching {
                        json.parseToJsonElement(call.receiveText()).jsonObject["deviceId"]
                            ?.jsonPrimitive?.contentOrNull
                    }.getOrNull().orEmpty()
                    if (mobileId.isNotBlank()) {
                        preferences.setConfig(preferences.config().copy(pairedDeviceId = mobileId))
                        logger("paired with mobile ${mobileId.take(12)}")
                    }
                    call.respondText(
                        """{"ok":true,"deviceId":"${preferences.config().selfDeviceId}"}""",
                        ContentType.Application.Json,
                    )
                }

                get("/sync/changes") {
                    if (!call.authorized()) return@get call.unauthorized()
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val bundle = syncService.changesSince(since)
                    call.respondText(
                        json.encodeToString(SyncBundle.serializer(), bundle),
                        ContentType.Application.Json,
                    )
                }

                post("/sync/upsert") {
                    if (!call.authorized()) return@post call.unauthorized()
                    val bundle = json.decodeFromString(SyncBundle.serializer(), call.receiveText())
                    syncService.applyFromPeer(bundle)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }

                get("/sync/subscribe") {
                    if (!call.authorized()) return@get call.unauthorized()
                    subscriberOpened()
                    try {
                        call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                            // Tell the phone to pull immediately, then on every local change.
                            write("data: changed\n\n"); flush()
                            syncService.localChanges.collect {
                                write("data: changed\n\n"); flush()
                            }
                        }
                    } finally {
                        subscriberClosed()
                    }
                }
            }
        }
        s.start(wait = false)
        server = s
        return s.resolvedConnectorsBlocking().first().port
    }

    private fun ApplicationCall.authorized(): Boolean {
        val token = preferences.config().pairingToken
        if (token.isBlank()) return false
        val header = request.headers["Authorization"] ?: return false
        return header.removePrefix("Bearer ").trim() == token
    }

    private suspend fun ApplicationCall.unauthorized() =
        respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
}

/** Blocking read of the resolved port (CIO resolves connectors asynchronously). */
private fun EmbeddedServer<*, *>.resolvedConnectorsBlocking() =
    kotlinx.coroutines.runBlocking { engine.resolvedConnectors() }
