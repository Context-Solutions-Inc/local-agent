package com.contextsolutions.localagent.subscription

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys
import com.contextsolutions.localagent.platform.UrlOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop orchestrator for paid "anywhere access" (PR #74). Owns the checkout +
 * claim + launch-validation flow on top of [RelayGatewayClient]:
 *
 *  - [startCheckout]: mint a nonce, build the loopback callback URL from the
 *    running link server's port, ask the gateway for a Stripe Checkout URL, open
 *    it in the browser, and start a fallback claim poll (covers the case where
 *    the browser redirect never reaches us).
 *  - [handleClaimCode]: invoked by `DesktopLinkServer`'s `/subscribe/callback`
 *    route after the browser returns; exchanges the one-time code for the
 *    credential.
 *  - [refresh]: launch-time re-validation; only runs when an account exists.
 *  - [openSubscriptionSettings]: opens the Stripe Customer Portal / account page.
 *
 * The account *secret* is written to [SecureStorage]; the non-secret ids + status
 * go to [SubscriptionPreferences]. [callbackPortProvider] is set by `Main.kt`
 * once the link server is bound.
 */
class RelaySubscriptionService(
    private val client: RelayGatewayClient,
    private val prefs: SubscriptionPreferences,
    private val secureStorage: SecureStorage,
    private val urlOpener: UrlOpener,
    private val scope: CoroutineScope,
    private val gatewayBaseUrl: String,
    private val subscriptionPortalUrl: String,
    var callbackPortProvider: () -> Int? = { null },
    private val logger: (String) -> Unit = {},
) {
    private val claimMutex = Mutex()

    // M3 (security) — the nonce minted by the most recent [startCheckout]. The
    // loopback `/subscribe/callback` is unauthenticated and browser-facing, so a
    // local process or a web page could POST/GET it an attacker-controlled
    // claim_code (CSRF). We bind the callback to this in-flight nonce: a callback
    // is only honored while a checkout WE started is pending and the URL carries
    // the matching nonce. Stripe preserves our `success_url` query string, so the
    // nonce rides back on the browser redirect with no gateway change.
    @Volatile
    private var pendingNonce: String? = null

    @Volatile
    private var pendingNonceExpiresAtMs: Long = 0L

    /** Reactive subscription state for the Settings UI. */
    fun stateFlow() = prefs.stateFlow()

    val isConfigured: Boolean get() = gatewayBaseUrl.isNotBlank()

    /** Begin the purchase flow. Returns a human-readable error, or null on success. */
    suspend fun startCheckout(): String? {
        if (!isConfigured) return "Anywhere access is not configured (no gateway URL)."
        val port = callbackPortProvider()
            ?: return "The desktop link server is not running yet. Try again in a moment."
        val nonce = newNonce()
        // The nonce rides on the redirect (Stripe preserves the success_url query
        // string) so the callback can prove it corresponds to a checkout we began.
        val redirect = "http://127.0.0.1:$port/subscribe/callback?nonce=$nonce"
        return when (val r = client.startCheckout(gatewayBaseUrl, nonce, redirect)) {
            is RelayGatewayClient.StartResult.Ok -> {
                val ttl = r.expiresInSec.coerceIn(60, 3600)
                pendingNonce = nonce
                pendingNonceExpiresAtMs = System.currentTimeMillis() + ttl * 1000L
                urlOpener.openUrl(r.checkoutUrl)
                startFallbackPoll(nonce, ttl)
                null
            }
            RelayGatewayClient.StartResult.Unavailable ->
                "Subscriptions are temporarily unavailable. Please try again later."
            is RelayGatewayClient.StartResult.Error -> "Could not start checkout: ${r.message}"
        }
    }

    /**
     * Called by the `/subscribe/callback` route. Returns true once the claim lands.
     * Rejected unless [nonce] matches the in-flight checkout's nonce (M3) — this
     * closes the loopback-CSRF path where an attacker drives the browser to the
     * callback with a code the desktop never requested.
     */
    suspend fun handleClaimCode(claimCode: String, nonce: String): Boolean {
        val expected = pendingNonce
        if (expected == null || nonce.isBlank() || nonce != expected ||
            System.currentTimeMillis() > pendingNonceExpiresAtMs
        ) {
            logger("rejected /subscribe/callback: no matching in-flight checkout nonce")
            return false
        }
        return when (val r = client.claim(gatewayBaseUrl, claimCode = claimCode)) {
            // The fallback nonce poll already redeemed this claim (no-Stripe flow);
            // the credential is being stored by that path, so report success.
            RelayGatewayClient.ClaimResult.AlreadyClaimed -> { pendingNonce = null; true }
            else -> applyClaim(r).also { if (it) pendingNonce = null }
        }
    }

    /** Launch-time re-validation; no-op when no account is stored locally. */
    suspend fun refresh() {
        val current = prefs.state()
        if (!current.hasAccount) return
        val secret = secureStorage.get(SecureStorageKeys.RELAY_ACCOUNT_SECRET)?.takeIf { it.isNotBlank() }
            ?: return
        val fetched = client.status(gatewayBaseUrl, secret) ?: return // offline → keep last-known
        prefs.update(
            current.copy(
                status = fetched.status,
                subscriptionId = fetched.subscriptionId.ifBlank { current.subscriptionId },
                licenseId = fetched.licenseId.ifBlank { current.licenseId },
                currentPeriodEndEpochSec = fetched.currentPeriodEndEpochSec,
            ),
        )
        logger("subscription refreshed: ${fetched.status}")
    }

    /**
     * Open "Subscription Settings": prefer a freshly-minted Stripe Customer Portal
     * URL from the gateway (manage/cancel the real subscription); fall back to a
     * statically-configured portal URL ([subscriptionPortalUrl]) if the gateway
     * path is unavailable (e.g. the portal isn't enabled in the Stripe dashboard).
     */
    suspend fun openSubscriptionSettings() {
        val secret = secureStorage.get(SecureStorageKeys.RELAY_ACCOUNT_SECRET)?.takeIf { it.isNotBlank() }
        val portal = if (isConfigured && secret != null) client.billingPortalUrl(gatewayBaseUrl, secret) else null
        val target = portal ?: subscriptionPortalUrl.takeIf { it.isNotBlank() }
        if (target != null) {
            urlOpener.openUrl(target)
        } else {
            logger("subscription settings unavailable (no portal URL)")
        }
    }

    private fun startFallbackPoll(nonce: String, ttlSec: Int) {
        scope.launch {
            val deadline = ttlSec * 1000L
            var waited = 0L
            val step = 2_500L
            while (isActive && waited < deadline) {
                if (prefs.state().isActive) return@launch // callback path already won
                when (val r = client.claim(gatewayBaseUrl, nonce = nonce)) {
                    is RelayGatewayClient.ClaimResult.Ok -> { applyClaim(r); return@launch }
                    RelayGatewayClient.ClaimResult.AlreadyClaimed -> return@launch // the callback path won
                    RelayGatewayClient.ClaimResult.Pending -> Unit // keep waiting for the webhook
                    is RelayGatewayClient.ClaimResult.Error -> Unit // transient; retry
                }
                delay(step)
                waited += step
            }
        }
    }

    private suspend fun applyClaim(result: RelayGatewayClient.ClaimResult): Boolean = claimMutex.withLock {
        if (result !is RelayGatewayClient.ClaimResult.Ok) return false
        if (result.accountId.isBlank() || result.accountSecret.isBlank()) return false
        if (prefs.state().isActive) return true // already claimed
        secureStorage.put(SecureStorageKeys.RELAY_ACCOUNT_SECRET, result.accountSecret)
        prefs.update(
            SubscriptionState(
                accountId = result.accountId,
                subscriptionId = result.subscriptionId,
                licenseId = result.licenseId,
                status = SubscriptionState.STATUS_VALID, // refresh() confirms below
            ),
        )
        logger("claimed account ${result.accountId.take(12)}…")
        refresh()
        true
    }

    private fun newNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "") +
        java.util.UUID.randomUUID().toString().take(4) // ≥ 22 chars, only hex

    companion object {
        /** Gateway base URL from the environment (draft: build-time/env, not a Settings field). */
        fun gatewayUrlFromEnv(): String = System.getenv("LOCALAGENT_GATEWAY_URL").orEmpty()

        /** Stripe Customer Portal / account URL for "Subscription Settings". */
        fun portalUrlFromEnv(): String = System.getenv("LOCALAGENT_SUBSCRIPTION_PORTAL_URL").orEmpty()

        /**
         * Relay WebSocket URL for the E2EE transport. Explicit env wins; otherwise
         * derived from the gateway base URL (`http→ws`, `https→wss`, `/v1/connect`).
         */
        fun relayWsUrlFromEnv(): String {
            System.getenv("LOCALAGENT_RELAY_WS_URL")?.takeIf { it.isNotBlank() }?.let { return it }
            val base = gatewayUrlFromEnv().trimEnd('/')
            if (base.isEmpty()) return ""
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/v1/connect"
        }
    }
}
