package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.DesktopHttpEngineFactory
import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.platform.UrlOpener
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * M3 (security) — the loopback `/subscribe/callback` is unauthenticated and
 * browser-facing, so [RelaySubscriptionService.handleClaimCode] must only redeem
 * a claim_code that arrives with the nonce of an in-flight checkout WE started.
 * This closes the loopback-CSRF path where a local process / web page drives the
 * browser to the callback with an attacker-controlled code.
 */
class RelaySubscriptionServiceTest {

    private var server: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server?.stop(0)
    }

    @Test
    fun `callback with no in-flight checkout is rejected and never contacts the gateway`() = runBlocking {
        val claimHits = AtomicInteger(0)
        val service = newService(startGateway(claimHits))

        // No startCheckout() ⇒ no pending nonce. A stray callback must be refused.
        val ok = service.handleClaimCode(claimCode = "attacker-code", nonce = "anything")

        assertFalse(ok, "a callback with no in-flight checkout must be rejected")
        assertEquals(0, claimHits.get(), "the gateway claim endpoint must not be hit")
        assertNull(storedSecret, "no account secret may be stored")
    }

    @Test
    fun `callback with a mismatched nonce is rejected after a checkout`() = runBlocking {
        val service = newService(startGateway(AtomicInteger(0)))

        assertNull(service.startCheckout()) // sets the in-flight nonce
        val ok = service.handleClaimCode(claimCode = "code", nonce = "not-the-real-nonce")

        assertFalse(ok, "a wrong nonce must be rejected")
        assertNull(storedSecret, "no account secret may be stored on a rejected callback")
    }

    @Test
    fun `callback with the matching in-flight nonce is honored`() = runBlocking {
        val service = newService(startGateway(AtomicInteger(0)))

        assertNull(service.startCheckout())
        val nonce = capturedNonce ?: error("checkout did not mint a nonce")
        val ok = service.handleClaimCode(claimCode = "code", nonce = nonce)

        assertTrue(ok, "the matching nonce must be honored")
        assertEquals("acct-secret-xyz", storedSecret)
    }

    // -- gateway (loopback HTTP) ---------------------------------------------

    @Volatile private var capturedNonce: String? = null

    private fun startGateway(claimHits: AtomicInteger): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/checkout/start") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                capturedNonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                respond(ex, 200, """{"checkout_url":"https://stripe.test/c","expires_in":600}""")
            }
            createContext("/v1/accounts/claim") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                if (body.contains("claim_code")) {
                    claimHits.incrementAndGet()
                    respond(
                        ex, 200,
                        """{"account_id":"acct-1","account_secret":"acct-secret-xyz","license_id":"lic-1","subscription_id":"sub-1"}""",
                    )
                } else {
                    respond(ex, 202, """{"status":"pending"}""") // fallback nonce poll: keep waiting
                }
            }
            createContext("/v1/subscription") { ex ->
                respond(ex, 200, """{"status":"valid","subscription_id":"sub-1","license_id":"lic-1"}""")
            }
            start()
        }
        server = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // -- fakes ----------------------------------------------------------------

    private var storedSecret: String? = null

    private fun newService(gatewayBaseUrl: String): RelaySubscriptionService {
        val gateway = RelayGatewayClient(DesktopHttpEngineFactory())
        return RelaySubscriptionService(
            client = gateway,
            prefs = InMemorySubscriptionPreferences(),
            secureStorage = InMemorySecureStorage(),
            urlOpener = object : UrlOpener { override fun openUrl(url: String) {} },
            scope = scope,
            gatewayBaseUrl = gatewayBaseUrl,
            subscriptionPortalUrl = "",
            callbackPortProvider = { 12345 },
        )
    }

    private class InMemorySubscriptionPreferences : SubscriptionPreferences {
        private val flow = MutableStateFlow(SubscriptionState.EMPTY)
        override fun state(): SubscriptionState = flow.value
        override fun stateFlow(): Flow<SubscriptionState> = flow.asStateFlow()
        override fun update(state: SubscriptionState) { flow.value = state }
        override fun clear() { flow.value = SubscriptionState.EMPTY }
    }

    private inner class InMemorySecureStorage : SecureStorage {
        private val map = HashMap<String, String>()
        override fun put(key: String, value: String) {
            map[key] = value
            if (key == SecureStorageKeys.RELAY_ACCOUNT_SECRET) storedSecret = value
        }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
