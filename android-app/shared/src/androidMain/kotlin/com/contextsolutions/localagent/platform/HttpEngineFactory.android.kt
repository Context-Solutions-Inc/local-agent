package com.contextsolutions.localagent.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class AndroidHttpEngineFactory : HttpEngineFactory {
    override fun create(block: HttpClientConfig<*>.() -> Unit): HttpClient =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000  // PRD section 3.3
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            install(Logging) {
                logger = RedactingLogger
                level = LogLevel.INFO
            }
            block()
        }
}

/**
 * Logs the request line only (method + host + path), never the query string or headers.
 * Brave search queries leave the device by definition (PRD section 4.4), but the rest
 * of the URL parameters and the API key header must never appear in logs.
 */
private object RedactingLogger : Logger {
    override fun log(message: String) {
        // Pass-through, but redact anything resembling an Authorization
        // header / X-Subscription-Token header / URL query string. The
        // pattern set lives in :shared/commonMain/observability/ContentRedactor
        // so M6 Phase D Crashlytics + telemetry/log paths share one
        // source of truth.
        val redacted = com.contextsolutions.localagent.observability.ContentRedactor.redact(message) ?: message
        android.util.Log.i("ktor", redacted)
    }
}
