package com.contextsolutions.mobileagent.observability

/**
 * Single chokepoint for crash + non-fatal exception reporting.
 *
 * **Why a facade (M6_PLAN §2 decision 18).** Firebase Crashlytics has no
 * `beforeSend` egress hook (unlike Sentry), so redaction has to happen
 * at the call site. Concentrating every `recordException` /
 * `setCustomKey` / `log` call behind this interface lets us:
 *
 *  1. Run every input through [ContentRedactor] before forwarding.
 *  2. Gate every outbound report on [TelemetryConsentManager] so a user
 *     who opted out doesn't accidentally see their crashes uploaded.
 *  3. Swap the backend (Sentry, custom) in v1.x or Phase 2 by changing
 *     one implementation, not every callsite.
 *  4. Catch a direct `FirebaseCrashlytics.getInstance(...).recordException(...)`
 *     call outside this facade via the Phase F lint rule — Crashlytics
 *     classes are only allowed in `:androidApp/.../observability/`.
 *
 * **No-op default.** Tests and the spike/stub builds use
 * [NoOpSafeCrashReporter] so they don't need a real `FirebaseApp` or
 * a Crashlytics dashboard to run. Production wiring binds the Firebase
 * implementation via Hilt.
 */
interface SafeCrashReporter {

    /**
     * Record a non-fatal exception. The throwable's message + stack
     * trace string args are run through [ContentRedactor] before
     * forwarding to the underlying SDK.
     *
     * Caller contract: do NOT put user content (chat text, memory
     * text, raw search queries) into exception messages or
     * `IllegalArgumentException("user said: $text")`-shaped throws.
     * The redactor is defense-in-depth, not a license to leak.
     */
    fun recordException(throwable: Throwable, context: Map<String, String> = emptyMap())

    /**
     * Log a breadcrumb — short, structured event that gives context to
     * the next crash. Examples: "model_loaded", "search_invoked",
     * "memory_extracted". Avoid user content; counts and short tag
     * values only.
     *
     * The message is run through [ContentRedactor] before forwarding.
     */
    fun log(message: String)

    /**
     * Set a sticky custom key that accompanies subsequent crash
     * reports. Examples: "active_accelerator=GPU", "preflight_band=high".
     * Values run through [ContentRedactor].
     */
    fun setCustomKey(key: String, value: String)

    /**
     * Pass-through for the SDK's own opt-in switch. Bound to the
     * `TelemetryConsentManager.enabledFlow` at app start so the user's
     * consent flips Crashlytics's internal collection on/off in real
     * time. When false, no crashes are uploaded even if [recordException]
     * is called (the SDK swallows; we belt-and-suspenders by no-opping
     * here too).
     */
    fun setCollectionEnabled(enabled: Boolean)

    /**
     * Force-flush any pending non-fatal reports. Crashlytics normally
     * batches `recordException` calls until the next app launch — fine
     * for production (saves battery + network round-trips), but a
     * nightmare for the debug leak-test flow which expects immediate
     * dashboard visibility. The debug button in Settings calls this
     * after `recordException` so the report ships within seconds.
     *
     * No-op when collection is disabled or when there are no pending
     * reports.
     */
    fun flushPending()
}

/**
 * No-op reporter for tests, the stub build, and any code path that
 * doesn't want Crashlytics noise.
 */
object NoOpSafeCrashReporter : SafeCrashReporter {
    override fun recordException(throwable: Throwable, context: Map<String, String>) = Unit
    override fun log(message: String) = Unit
    override fun setCustomKey(key: String, value: String) = Unit
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun flushPending() = Unit
}
