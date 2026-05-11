package com.contextsolutions.mobileagent.inference

/**
 * Resolves the effective HuggingFace API token used to authenticate the Gemma 4
 * weights download (the upstream repo is gated).
 *
 * Mirrors `BraveKeyProvider` in intent and shape: the user-supplied token stored
 * in `SecureStorage` is the primary source; internal builds may fall back to a
 * bundled dev value from `BuildConfig.HF_AUTH_TOKEN` so engineers don't have to
 * configure their own token to exercise the download path end-to-end. Production
 * builds never carry a bundled token — production users provide their own via
 * the onboarding flow or Settings.
 *
 * Implementations must:
 *  - return `null` when no token is available (the downloader sends the request
 *    without an `Authorization` header — fine for ungated mirrors, will surface
 *    as a 401/403 for the gated HuggingFace artifact)
 *  - never log the returned value
 *  - prefer the user-set token over the bundled dev token when both are present
 */
interface HfAuthTokenProvider {
    /** The token to attach to the model-download request, or `null` to send no Authorization header. */
    fun currentToken(): String?

    /** True when [currentToken] would return a non-null, non-blank value. Cheap check for UI gating. */
    fun hasToken(): Boolean = !currentToken().isNullOrBlank()
}
