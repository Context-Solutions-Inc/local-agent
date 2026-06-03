package com.contextsolutions.mobileagent.sync

import com.contextsolutions.mobileagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Mobile-side HTTP client for the desktop link server's sync endpoints (PR #57).
 * Short calls (fetch/push) use a tight-timeout client; the [subscribe] SSE holds
 * a long-lived connection on a dedicated un-timed client (the default 10 s
 * request timeout would abort the stream). Every call carries the pairing token.
 */
class LinkSyncHttpClient(httpEngineFactory: HttpEngineFactory) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client: HttpClient = httpEngineFactory.create()
    private val streamClient: HttpClient = httpEngineFactory.create {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = null
            socketTimeoutMillis = null
        }
    }

    /** GET `/sync/changes?since=` → the peer's change bundle, or null if unreachable. */
    suspend fun fetchChanges(baseUrl: String, token: String, sinceMs: Long): SyncBundle? = try {
        val response = client.get("${baseUrl.trimEnd('/')}/sync/changes?since=$sinceMs") {
            header(HttpHeaders.Authorization, "Bearer $token")
            timeout { requestTimeoutMillis = CALL_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }
        if (!response.status.isSuccess()) null
        else json.decodeFromString(SyncBundle.serializer(), response.bodyAsText())
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    /** POST `/sync/upsert` with a local change bundle. Returns true on 2xx. */
    suspend fun pushChanges(baseUrl: String, token: String, bundle: SyncBundle): Boolean = try {
        client.post("${baseUrl.trimEnd('/')}/sync/upsert") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SyncBundle.serializer(), bundle))
            timeout { requestTimeoutMillis = CALL_TIMEOUT_MS; connectTimeoutMillis = CONNECT_TIMEOUT_MS }
        }.status.isSuccess()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    /**
     * Subscribe to the desktop's change SSE. Emits Unit on the initial connect
     * and on every desktop-side change; the collector reconciles on each. The
     * flow completes/throws when the connection drops (the caller restarts it).
     */
    fun subscribe(baseUrl: String, token: String): Flow<Unit> = flow {
        streamClient.prepareGet("${baseUrl.trimEnd('/')}/sync/subscribe") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute
            val channel = response.bodyAsChannel()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith("data:")) emit(Unit)
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
