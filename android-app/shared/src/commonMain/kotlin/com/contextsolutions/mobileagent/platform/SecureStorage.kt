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
}
