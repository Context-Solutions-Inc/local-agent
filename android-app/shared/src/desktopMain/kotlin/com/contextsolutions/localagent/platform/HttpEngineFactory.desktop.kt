package com.contextsolutions.localagent.platform

import com.contextsolutions.localagent.observability.ContentRedactor
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Desktop HttpEngineFactory — same configuration as [AndroidHttpEngineFactory] but
 * on Ktor's pure-Kotlin CIO engine (no OkHttp/Android). Timeouts and the
 * query-string-redacting logger are preserved so search behaves identically and
 * Brave keys / query strings never reach desktop logs (PRD §4.4).
 */
class DesktopHttpEngineFactory : HttpEngineFactory {
    override fun create(block: HttpClientConfig<*>.() -> Unit): HttpClient =
        HttpClient(CIO) {
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
            install(Logging) {
                logger = RedactingLogger
                level = LogLevel.INFO
            }
            block()
        }
}

private object RedactingLogger : Logger {
    override fun log(message: String) {
        val redacted = ContentRedactor.redact(message) ?: message
        println("[ktor] $redacted")
    }
}
