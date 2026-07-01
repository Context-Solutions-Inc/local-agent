package com.contextsolutions.localagent.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * iOS [HttpEngineFactory] (PR #41) — Ktor's Darwin engine (NSURLSession), the
 * iOS counterpart of [AndroidHttpEngineFactory] (OkHttp) / [DesktopHttpEngineFactory]
 * (CIO). Same JSON content-negotiation + timeouts so the Ollama/search/link clients
 * behave identically. Per-request logging is off (no query strings / keys in logs,
 * PRD §4.4).
 */
class IosHttpEngineFactory : HttpEngineFactory {
    override fun create(block: HttpClientConfig<*>.() -> Unit): HttpClient =
        HttpClient(Darwin) {
            // Force uncompressed responses. NSURLSession auto-adds `Accept-Encoding: gzip`
            // and transparently decompresses, but Ktor's Darwin engine then validates the
            // *decompressed* body length against the server's original (compressed)
            // `Content-Length` header and throws "Content-Length mismatch" — e.g. the
            // weather.gov DWML fetch (2308 gzipped → 11724 bytes). Requesting `identity`
            // makes the server send plain bytes with a truthful Content-Length. Setting an
            // explicit Accept-Encoding also disables NSURLSession's automatic decompression,
            // so no double-handling. (Only iOS/Darwin needs this — OkHttp/CIO handle it.)
            install(DefaultRequest) {
                header(HttpHeaders.AcceptEncoding, "identity")
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            block()
        }
}
