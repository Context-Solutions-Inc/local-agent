package com.contextsolutions.mobileagent.inference

import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Thin Ktor client for the paired desktop agent's link server (PR #57)
 * *control-plane* — a reachability probe ([health]) used by both
 * [DesktopLinkInferenceEngine.loadModel] (decide whether to route chat to the
 * desktop) and the desktop-link [OllamaConnectionMonitor] reconnect-watch + the
 * chat header's link-status dot. The streaming generation path lives in
 * [DesktopLinkInferenceEngine]; kept separate for the same reason as
 * [OllamaClient] — generation wants an un-timed client, this wants a tight one.
 *
 * Every call carries the QR-provisioned pairing token as `Authorization: Bearer`
 * so a desktop ignores un-paired LAN clients (plain HTTP by design — trusted LAN,
 * no SSL).
 */
class DesktopLinkClient internal constructor(private val client: HttpClient) {

    constructor(httpEngineFactory: HttpEngineFactory) : this(httpEngineFactory.create())

    /**
     * GET `<baseUrl>/health` with the pairing token. True iff the desktop link
     * server answers 200 — i.e. it's reachable AND accepts our token. Tight
     * timeout so an unreachable host fails the first turn fast (→ local fallback).
     */
    suspend fun health(baseUrl: String, token: String): Boolean = try {
        client.get("${baseUrl.trimEnd('/')}/health") {
            header(HttpHeaders.Authorization, "Bearer $token")
            timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }.status.isSuccess()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    /**
     * POST `<baseUrl>/pair` with our own device id so the desktop records this
     * phone as the paired peer. Returns true on 2xx. Called right after a QR scan.
     */
    suspend fun pair(baseUrl: String, token: String, selfDeviceId: String): Boolean = try {
        client.post("${baseUrl.trimEnd('/')}/pair") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"$selfDeviceId"}""")
            timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }.status.isSuccess()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val HEALTH_TIMEOUT_MS = 3_000L
    }
}
