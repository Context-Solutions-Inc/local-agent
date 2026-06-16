package com.contextsolutions.localagent.observability

import com.contextsolutions.localagent.telemetry.TelemetryConsentManager
import io.sentry.Sentry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop [SafeCrashReporter] (docs/DESKTOP_PORT_PLAN.md, Phase 7) backed by the
 * Sentry JVM SDK. The Android counterpart is Firebase-Crashlytics-backed (#23).
 *
 * **Redaction discipline (#24).** Sentry *does* expose a `beforeSend` egress
 * hook, but this reporter keeps the same redaction-at-callsite discipline as
 * `FirebaseSafeCrashReporter`: every message / custom-key / breadcrumb runs
 * through [ContentRedactor], and exceptions are wrapped in [RedactedThrowable]
 * (scrubbed message, preserved stack + original class name) before
 * `captureException`. Keeping the scrub at the callsite means the contract holds
 * regardless of SDK configuration — defense in depth.
 *
 * **Consent gate (#27, PRD §3.2.1).** [collectionEnabled] is seeded from
 * [TelemetryConsentManager] at construction and flipped by [setCollectionEnabled]
 * (the app wires `consent.enabledFlow` to it at startup). When OFF, every report
 * path is a no-op — nothing reaches Sentry even if `recordException` is called.
 *
 * **DSN gate.** Sentry initialises only when a DSN is present (env `SENTRY_DSN`,
 * or supplied directly). Without one — as in CI / a fresh install — the reporter
 * degrades to a local logger and never makes a network call, so the graph
 * resolves and runs headless. Real upload is an operator's manual check.
 */
class SentrySafeCrashReporter(
    consent: TelemetryConsentManager,
    private val dsn: String? = System.getenv("SENTRY_DSN"),
    private val environment: String = "desktop",
    private val release: String? = System.getenv("LOCALAGENT_RELEASE"),
    private val logger: (String) -> Unit = { System.err.println("[Sentry] $it") },
) : SafeCrashReporter {

    private val collectionEnabled = AtomicBoolean(consent.enabled())
    private val active: Boolean = initSentry()

    private fun initSentry(): Boolean {
        val effectiveDsn = dsn?.takeIf { it.isNotBlank() } ?: return false
        return try {
            Sentry.init { options ->
                options.dsn = effectiveDsn
                options.environment = environment
                release?.takeIf { it.isNotBlank() }?.let { options.release = it }
                // Crash reports only — no performance traces, no PII. The
                // redaction below is the content guard; this disables Sentry's
                // own automatic PII capture as a second layer.
                options.isSendDefaultPii = false
            }
            true
        } catch (t: Throwable) {
            logger("init failed: ${t.message}")
            false
        }
    }

    private fun reporting(): Boolean = active && collectionEnabled.get()

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        if (!reporting()) return
        for ((key, value) in context) {
            ContentRedactor.redact(value)?.let { redacted -> Sentry.setTag(key, redacted) }
        }
        Sentry.captureException(ContentRedactor.redactThrowable(throwable))
    }

    override fun log(message: String) {
        if (!reporting()) return
        val redacted = ContentRedactor.redact(message) ?: return
        Sentry.addBreadcrumb(redacted)
    }

    override fun setCustomKey(key: String, value: String) {
        if (!reporting()) return
        val redacted = ContentRedactor.redact(value) ?: return
        Sentry.setTag(key, redacted)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        collectionEnabled.set(enabled)
    }

    override fun flushPending() {
        if (!active) return
        // Force any queued events out (the desktop analogue of Crashlytics
        // sendUnsentReports). 2 s mirrors the SDK's default flush timeout.
        Sentry.flush(FLUSH_TIMEOUT_MS)
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 2_000L
    }
}
