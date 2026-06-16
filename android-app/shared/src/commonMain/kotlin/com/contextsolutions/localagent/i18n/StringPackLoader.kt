package com.contextsolutions.localagent.i18n

/**
 * Platform seam for fetching a language pack's raw JSON by ISO code. Injected
 * (Koin) rather than `expect/actual` so the `iosMain` set needs no stub and it
 * matches the existing asset-in-DI convention:
 *  - Android reads `assets/i18n/strings_<code>.json`.
 *  - Desktop reads the same path from the classpath (`DesktopResources`).
 *
 * Returns `null` when no pack exists for [code] (the common case today — only
 * the in-code English floor ships, so every lookup falls back to English). A
 * future remote-pack story checks app-data before the bundled assets — a
 * one-line change here, the seam already supports it.
 */
fun interface StringPackLoader {
    suspend fun load(code: String): String?

    companion object {
        /** No packs available — every code resolves to the English floor. */
        val None: StringPackLoader = StringPackLoader { null }
    }
}
