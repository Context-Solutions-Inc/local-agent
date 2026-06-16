package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.HttpEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Thin Ktor client for the Secure Gateway auth service's HTTP control plane used
 * by the desktop subscription flow (PR #74): start a Stripe Checkout, claim the
 * account credential after payment, and re-validate on launch. The E2EE relay
 * websocket is NOT here — that is the gateway SDK's `DesktopClient` (pairing QR +
 * transport). Built from the platform [HttpEngineFactory] like [OllamaClient];
 * bodies/responses are hand-encoded JSON (no typed ContentNegotiation) so this
 * has no serializer-registration coupling.
 */
class RelayGatewayClient internal constructor(
    private val client: HttpClient,
    private val logger: (String) -> Unit = {},
) {
    constructor(httpEngineFactory: HttpEngineFactory, logger: (String) -> Unit = {}) :
        this(httpEngineFactory.create(), logger)

    /** Result of [startCheckout]. */
    sealed interface StartResult {
        data class Ok(val checkoutUrl: String, val expiresInSec: Int) : StartResult
        /** The gateway has no price configured (AUTH_STRIPE_PRICE_ID unset). */
        data object Unavailable : StartResult
        data class Error(val message: String) : StartResult
    }

    suspend fun startCheckout(baseUrl: String, nonce: String, redirectUri: String): StartResult = try {
        val url = "${baseUrl.trimEnd('/')}/v1/checkout/start"
        logger("POST $url")
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("nonce", nonce)
                put("redirect_uri", redirectUri)
            }.toString())
            timeout { requestTimeoutMillis = TIMEOUT_MS; connectTimeoutMillis = CONNECT_MS }
        }
        when (resp.status.value) {
            200 -> {
                val obj = JSON.parseToJsonElement(resp.bodyAsText()).jsonObject
                val checkout = obj.string("checkout_url")
                if (checkout.isNullOrBlank()) StartResult.Error("no checkout_url")
                else StartResult.Ok(checkout, (obj["expires_in"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0)
            }
            503 -> StartResult.Unavailable
            else -> StartResult.Error("status ${resp.status.value}")
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        StartResult.Error(t.message ?: "network error")
    }

    /** Result of [claim]. */
    sealed interface ClaimResult {
        data class Ok(
            val accountId: String,
            val accountSecret: String,
            val licenseId: String,
            val subscriptionId: String,
        ) : ClaimResult
        /** Webhook hasn't landed yet — caller should retry with backoff. */
        data object Pending : ClaimResult
        data class Error(val message: String) : ClaimResult
    }

    /** Exchange a one-time claim code (or the held nonce) for the account credential. */
    suspend fun claim(baseUrl: String, claimCode: String? = null, nonce: String? = null): ClaimResult = try {
        val url = "${baseUrl.trimEnd('/')}/v1/accounts/claim"
        logger("POST $url")
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                claimCode?.let { put("claim_code", it) }
                nonce?.let { put("nonce", it) }
            }.toString())
            timeout { requestTimeoutMillis = TIMEOUT_MS; connectTimeoutMillis = CONNECT_MS }
        }
        when (resp.status.value) {
            200 -> {
                val o = JSON.parseToJsonElement(resp.bodyAsText()).jsonObject
                ClaimResult.Ok(
                    accountId = o.string("account_id").orEmpty(),
                    accountSecret = o.string("account_secret").orEmpty(),
                    licenseId = o.string("license_id").orEmpty(),
                    subscriptionId = o.string("subscription_id").orEmpty(),
                )
            }
            202 -> ClaimResult.Pending
            else -> ClaimResult.Error("status ${resp.status.value}")
        }
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        ClaimResult.Error(t.message ?: "network error")
    }

    /**
     * Launch-time validation: GET /v1/subscription with the account secret. Returns
     * null on any failure so the caller keeps its last-known state (offline-tolerant).
     */
    suspend fun status(baseUrl: String, accountSecret: String): SubscriptionState? = try {
        val url = "${baseUrl.trimEnd('/')}/v1/subscription"
        logger("GET $url")
        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $accountSecret")
            timeout { requestTimeoutMillis = TIMEOUT_MS; connectTimeoutMillis = CONNECT_MS }
        }
        if (resp.status.value != 200) {
            // 401/403 → revoked/unknown account; surface as revoked so the UI downgrades.
            if (resp.status.value == 401 || resp.status.value == 403) {
                SubscriptionState(status = SubscriptionState.STATUS_REVOKED)
            } else {
                null
            }
        } else {
            val o = JSON.parseToJsonElement(resp.bodyAsText()).jsonObject
            SubscriptionState(
                accountId = "", // preserved by the caller; not returned here
                subscriptionId = o.string("subscription_id").orEmpty(),
                licenseId = o.string("license_id").orEmpty(),
                status = o.string("status") ?: SubscriptionState.STATUS_UNKNOWN,
                currentPeriodEndEpochSec = (o["current_period_end"] as? JsonPrimitive)?.let {
                    runCatching { it.long }.getOrNull()
                } ?: 0L,
            )
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    /**
     * Mint a Stripe Customer Portal URL for "Subscription Settings". Returns null
     * on any failure (e.g. the portal isn't enabled in the Stripe dashboard).
     */
    suspend fun billingPortalUrl(baseUrl: String, accountSecret: String): String? = try {
        val url = "${baseUrl.trimEnd('/')}/v1/billing-portal"
        logger("POST $url")
        val resp = client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $accountSecret")
            contentType(ContentType.Application.Json)
            setBody("{}")
            timeout { requestTimeoutMillis = TIMEOUT_MS; connectTimeoutMillis = CONNECT_MS }
        }
        if (resp.status.value == 200) {
            JSON.parseToJsonElement(resp.bodyAsText()).jsonObject.string("url")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val CONNECT_MS = 3_000L
        const val TIMEOUT_MS = 8_000L
    }
}
