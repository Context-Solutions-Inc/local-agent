package com.contextsolutions.mobileagent.platform

/**
 * Platform-secure key/value storage for the Brave API key and other secrets.
 *
 * Android: EncryptedSharedPreferences (AndroidX Security crypto, AES256-GCM).
 * iOS (Phase 2): Keychain.
 *
 * Per PRD section 4.4, the Brave API key must never be logged and must never be
 * written to plaintext disk. Construction is platform-specific (Android needs a
 * Context), so the factory is owned by the platform-side DI module rather than a
 * shared no-arg constructor.
 */
interface SecureStorage {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun contains(key: String): Boolean
}

object SecureStorageKeys {
    const val BRAVE_API_KEY = "brave_api_key"
    const val TELEMETRY_OPT_IN = "telemetry_opt_in"
    /** "true"/"false". Default behavior when unset is enabled (search on). */
    const val SEARCH_ENABLED = "search_enabled"
    /**
     * HuggingFace API token used to authenticate the Gemma 4 weights download
     * (the upstream repo is gated). Same BYOK pattern as [BRAVE_API_KEY] —
     * user-supplied keys override the bundled internal-build dev value; release
     * builds never bundle one. Resolved via [com.contextsolutions.mobileagent.inference.HfAuthTokenProvider].
     */
    const val HF_AUTH_TOKEN = "hf_auth_token"

    /**
     * Optional API key for the remote chat LLM (PR #58). When set, it rides every
     * outbound request to the configured Ollama / OpenAI-compatible server as an
     * `Authorization: Bearer <key>` header (see
     * [com.contextsolutions.mobileagent.inference.OllamaInferenceEngine] +
     * [com.contextsolutions.mobileagent.inference.OllamaClient]); when unset, no
     * auth header is sent — the pre-#58 default. Same BYOK + encrypted-at-rest
     * pattern as [BRAVE_API_KEY]; read per-request so a change applies next turn.
     */
    const val OLLAMA_API_KEY = "ollama_api_key"

    /**
     * Secure Gateway account secret for paid "anywhere access" (PR #74). Minted
     * once by the gateway at checkout-claim time (form `acct_….<rand>`) and used
     * as the `Authorization: Bearer` credential for the gateway's authenticated
     * endpoints (pairing-token, token issue, `GET /v1/subscription`). Desktop-only.
     * Same encrypted-at-rest, never-logged discipline as [BRAVE_API_KEY]; the
     * account_id/subscription_id/license_id (non-secret) live in
     * `subscription_prefs.json`, never here.
     */
    const val RELAY_ACCOUNT_SECRET = "relay_account_secret"

    /**
     * Persisted result of a completed relay pairing (PR #77 follow-up), as a small JSON blob
     * `{pairingToken, deviceId, pairId, desktopPublicKey}`. The relay QR's pairing token is
     * single-use, so a reconnect (Desktop Agent Connection toggle off→on, or an app relaunch)
     * cannot replay it — it would fail `401 pairing_token_invalid`. Instead the phone reuses
     * this to call `MobileClient.connect()` directly, skipping `pair()`. Keyed by the scanned
     * QR's `pairingToken`: a freshly scanned QR (new token, e.g. after a desktop re-mint) no
     * longer matches, so the phone pairs fresh and overwrites this. Not a high-value secret
     * (the account secret stays in [RELAY_ACCOUNT_SECRET]); co-located here for one encrypted
     * store on the relay credential path.
     */
    const val RELAY_PAIRING_STATE = "relay_pairing_state"

    /**
     * The desktop's relay device id (`dev_…`), persisted on first pairing-QR mint and reused
     * across restarts (PR #77 follow-up). The desktop X25519 identity already persists (the
     * `relay_identity.key` keystore file), but the gateway *device id* did not — so every
     * restart/re-mint registered a NEW device, and with the old pairing still holding the
     * account's single `max_pairs` slot the gateway returned `capacity_exceeded`, leaving the
     * desktop on the LAN QR. Restoring the same device id makes the gateway treat the mint as
     * a re-pair (reuses the slot, FR-2.2). Desktop-only; not a secret (co-located with the
     * relay account secret for one store on the relay credential path).
     */
    const val RELAY_DESKTOP_DEVICE_ID = "relay_desktop_device_id"
}
