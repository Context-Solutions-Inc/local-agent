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
}
