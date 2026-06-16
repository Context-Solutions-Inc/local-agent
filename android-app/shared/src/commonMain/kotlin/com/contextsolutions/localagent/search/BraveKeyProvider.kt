package com.contextsolutions.localagent.search

/**
 * Resolves the effective Brave Search API key for the current session.
 *
 * Per PRD section 3.6 / 4.4 the user-supplied key (stored in [SecureStorage]) is
 * the primary source. Internal builds may fall back to a bundled dev key from
 * `BuildConfig.BRAVE_DEV_KEY` so engineers don't have to configure their own key
 * to test search end-to-end. Production builds never carry a bundled key.
 *
 * Implementations must:
 *  - return `null` when no key is available (search is disabled, not an error)
 *  - never log the returned value
 *  - prefer the user-set key over the bundled dev key when both are present
 *
 * The seam exists in commonMain so the search client (WS-4) can be written
 * platform-agnostically; Android wiring lives in [androidMain].
 */
interface BraveKeyProvider {
    /** The key to attach to outbound Brave Search requests, or `null` if search should be disabled. */
    fun currentKey(): String?

    /** True when [currentKey] would return a non-null, non-blank value. Cheap check for UI gating. */
    fun hasKey(): Boolean = !currentKey().isNullOrBlank()
}
