package com.contextsolutions.localagent.search

import com.contextsolutions.localagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct request-construction coverage for [KtorBraveLlmContextClient] — the gap
 * left when PR #41 added the client (the suite previously mocked at the
 * [SearchService]/`FakeBraveSearchClient` layer). PR #42 makes this client the
 * default for every Brave-backed vertical, so the per-vertical `maxUrls` budget,
 * the budget query params, the endpoint path, and the auth header are now
 * load-bearing across the app. Drives a [MockEngine] through the public
 * [HttpEngineFactory] seam (the primary HttpClient constructor is `internal` to
 * `:shared`).
 */
class KtorBraveLlmContextClientTest {

    /** Captures the last outgoing request and replies with [responseBody]/[status]. */
    private class CapturingFactory(
        private val responseBody: String = VALID_BODY,
        private val status: HttpStatusCode = HttpStatusCode.OK,
    ) : HttpEngineFactory {
        var lastRequest: HttpRequestData? = null

        override fun create(block: HttpClientConfig<*>.() -> Unit): HttpClient =
            HttpClient(
                MockEngine { request ->
                    lastRequest = request
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { block() }
    }

    @Test
    fun `hits the llm-context endpoint with the injected url budget and auth header`() = runTest {
        val factory = CapturingFactory()
        val client = KtorBraveLlmContextClient(factory, maxUrls = 3)

        val result = client.search("who won the masters", "test-key")

        val req = requireNotNull(factory.lastRequest)
        assertEquals("/res/v1/llm/context", req.url.encodedPath)
        assertEquals("who won the masters", req.url.parameters["q"])
        // Injected per-vertical URL budget rides through to the wire.
        assertEquals("3", req.url.parameters["maximum_number_of_urls"])
        assertEquals("1800", req.url.parameters["maximum_number_of_tokens"])
        assertEquals("6", req.url.parameters["maximum_number_of_snippets_per_url"])
        assertEquals("test-key", req.headers[KtorBraveLlmContextClient.SUBSCRIPTION_TOKEN_HEADER])
        assertTrue(result is BraveSearchResult.Success)
    }

    @Test
    fun `defaults the url budget to 1 (the SPORTS single-source value)`() = runTest {
        val factory = CapturingFactory()
        // No maxUrls passed: the bare-factory default must stay 1 so SPORTS keeps
        // its single clean source (invariant #35) without an explicit argument.
        val client = KtorBraveLlmContextClient(factory)

        client.search("raptors score", "k")

        assertEquals("1", requireNotNull(factory.lastRequest).url.parameters["maximum_number_of_urls"])
    }

    @Test
    fun `maps a 401 to an Auth error without crashing`() = runTest {
        val factory = CapturingFactory(responseBody = "", status = HttpStatusCode.Unauthorized)
        val client = KtorBraveLlmContextClient(factory, maxUrls = 3)

        val result = client.search("q", "bad-key")

        assertTrue(result is BraveSearchResult.Error)
        assertEquals(BraveSearchResult.ErrorKind.Auth, (result as BraveSearchResult.Error).kind)
    }

    private companion object {
        // Minimal grounding payload that LlmContextPostProcessor turns into one
        // non-empty source, so a 200 yields Success.
        const val VALID_BODY =
            """{"grounding":{"generic":[{"url":"https://espn.com/scores",""" +
                """"title":"Scores","snippets":["Cavaliers 114, Raptors 102"]}]}}"""
    }
}
