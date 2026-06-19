package com.contextsolutions.localagent.link

import com.contextsolutions.localagent.preferences.DesktopLinkConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Small desktop-loopback Ktor (CIO) HTTP server. Since PR #80 removed the LAN link
 * (the relay is now the only mobile↔desktop pairing path), this server only hosts
 * the subscription onboarding callback + a liveness probe:
 *
 *  - `GET /ping` — unauthenticated liveness (used to find the bound port).
 *  - `GET /subscribe/callback` — PR #74 Stripe Checkout success redirect
 *    (browser-facing). The browser is redirected here by the gateway after payment;
 *    the handler exchanges the one-time `claim_code` for the account credential.
 *    Unauthenticated (the browser has no bearer); the loopback binding + a single-use
 *    code are the controls.
 *
 * Bound on loopback (`127.0.0.1`) — nothing here is meant for other LAN hosts.
 */
class DesktopLinkServer(
    /**
     * PR #74 — handles the Stripe Checkout success redirect (`GET
     * /subscribe/callback?claim_code=…&nonce=…`). Returns true on success. The
     * `nonce` binds the callback to the in-flight checkout (M3 — loopback CSRF).
     */
    private val onSubscribeCallback: (suspend (claimCode: String, nonce: String) -> Boolean)? = null,
    private val preferredPort: Int = DesktopLinkConfig.DEFAULT_PORT,
    private val logger: (String) -> Unit = {},
) {
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var boundPort: Int = 0
        private set

    /** Start the server, return the bound port. */
    fun start(): Int {
        val port = runCatching { startOn(preferredPort) }.getOrElse {
            logger("port $preferredPort unavailable (${it.message}); using an ephemeral port")
            startOn(0)
        }
        boundPort = port
        logger("listening on 127.0.0.1:$port")
        return port
    }

    fun stop() {
        runCatching { server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000) }
        server = null
    }

    private fun startOn(port: Int): Int {
        val s = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger("route error: ${cause.message}")
                    call.respondText(
                        """{"error":${jsonString(cause.message ?: "error")}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                }
            }
            routing {
                get("/ping") { call.respondText("ok") }

                // PR #74 — Stripe Checkout success redirect. Browser-facing (no
                // bearer); the gateway only ever redirects a one-time claim_code
                // to this loopback address.
                get("/subscribe/callback") {
                    val code = call.request.queryParameters["claim_code"].orEmpty()
                    val nonce = call.request.queryParameters["nonce"].orEmpty()
                    val canceled = call.request.queryParameters["status"] == "canceled"
                    val ok = !canceled && code.isNotBlank() &&
                        (onSubscribeCallback?.invoke(code, nonce) ?: false)
                    val message = when {
                        canceled -> "Checkout canceled. You can close this tab."
                        ok -> "Subscription activated. You can return to the Local Agent app."
                        else -> "Could not finish activation. Return to the app and try again."
                    }
                    call.respondText(
                        "<!doctype html><meta charset=utf-8><title>Local Agent</title>" +
                            "<body style=\"font-family:sans-serif;text-align:center;margin-top:4em\">" +
                            "<h2>$message</h2></body>",
                        ContentType.Text.Html,
                    )
                }
            }
        }
        s.start(wait = false)
        server = s
        return s.resolvedConnectorsBlocking().first().port
    }
}

/** Minimal JSON string-escape for the error wrapper (avoids pulling a serializer for one field). */
private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

/** Blocking read of the resolved port (CIO resolves connectors asynchronously). */
private fun EmbeddedServer<*, *>.resolvedConnectorsBlocking() =
    kotlinx.coroutines.runBlocking { engine.resolvedConnectors() }
