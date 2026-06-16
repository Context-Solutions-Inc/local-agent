package com.contextsolutions.localagent.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Builds the platform-appropriate Ktor HttpClient. The agent uses a single client
 * lifetime — created once at app startup, used for every Brave Search request.
 *
 * Android: OkHttp engine with a request-line-only logging interceptor that scrubs
 * query strings out of error logs (per PRD section 4.4).
 * iOS (Phase 2): Darwin engine.
 */
interface HttpEngineFactory {
    fun create(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient
}
