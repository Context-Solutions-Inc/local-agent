package com.contextsolutions.localagent.observability

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Production [SafeCrashReporter] backed by Firebase Crashlytics.
 *
 * Every input passes through [ContentRedactor] before reaching the
 * SDK. The Crashlytics SDK has no `beforeSend` hook (PRD §4.4 forbids
 * memory / message content in crash reports; without an egress hook we
 * must scrub at the call site).
 *
 * `recordException` wraps the throwable in a `RedactedThrowable` whose
 * message has been scrubbed; the original stack trace is preserved
 * (`Throwable.stackTrace` is copied). The original throwable's class
 * name is encoded in the redacted message so the dashboard still
 * indicates what kind of exception fired.
 */
class FirebaseSafeCrashReporter : SafeCrashReporter {

    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    override fun recordException(throwable: Throwable, context: Map<String, String>) {
        for ((key, value) in context) {
            ContentRedactor.redact(value)?.let { redacted -> crashlytics.setCustomKey(key, redacted) }
        }
        val redacted = ContentRedactor.redactThrowable(throwable)
        crashlytics.recordException(redacted)
    }

    override fun log(message: String) {
        val redacted = ContentRedactor.redact(message) ?: return
        crashlytics.log(redacted)
    }

    override fun setCustomKey(key: String, value: String) {
        val redacted = ContentRedactor.redact(value) ?: return
        crashlytics.setCustomKey(key, redacted)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun flushPending() {
        // Crashlytics queues non-fatals locally and uploads them on the
        // next app launch. sendUnsentReports forces immediate upload —
        // primarily useful for the debug leak-test flow. No-op when
        // collection is disabled or the queue is empty.
        crashlytics.sendUnsentReports()
    }
}
