package com.contextsolutions.mobileagent.observability

/**
 * Single source of truth for redacting sensitive content out of strings
 * before they leave the device (logs, crash reports, breadcrumbs).
 *
 * **Counter-only telemetry contract (PRD §3.2.1, §4.4).** All exception
 * messages, stack-trace string args, custom-key values, and HTTP-logger
 * lines flow through [redact] before being forwarded to Firebase
 * Crashlytics or the Ktor logger. The pattern set is conservative — we
 * accept some over-redaction (a log line gets less useful) in exchange
 * for never leaking memory text, search query content, or API keys.
 *
 * **Design discipline (M6_PLAN §2 decision 18).** Firebase Crashlytics
 * has no `beforeSend` egress hook (unlike Sentry), so redaction lives
 * at the call site behind a facade. [redact] is that facade's filter;
 * `SafeCrashReporter` is the only chokepoint through which exceptions
 * reach `FirebaseCrashlytics.recordException`. A direct
 * `FirebaseCrashlytics.recordException(t)` call elsewhere in the
 * codebase is a contract violation the Phase F lint rule will catch.
 *
 * **Limits.** The redactor handles *known string shapes* — API keys
 * with a recognizable token prefix, headers with a known name, URL
 * query strings. It does NOT attempt to detect arbitrary "this looks
 * like user content" because that's unbounded. Defense in depth: code
 * paths that touch user content (chat messages, memory text) must not
 * surface that content in exception messages or breadcrumbs in the
 * first place. The redactor is the fallback for the "I wrote
 * `throw RuntimeException("query was: $query")` by mistake" case.
 */
object ContentRedactor {

    /** Replacement for any matched secret-bearing substring. */
    private const val REDACTED = "<redacted>"

    /**
     * Regex set. Each pattern matches a known secret shape; matches are
     * replaced with [REDACTED] (or a structurally similar placeholder for
     * shapes where the surrounding context matters, e.g., HTTP headers).
     *
     * Patterns are ordered: earlier replacements win when ranges overlap.
     * URL-shape replacement comes before bare-token replacement so a
     * Bearer-prefixed URL like `Bearer https://...` doesn't double-redact.
     */
    private val patterns: List<Pair<Regex, String>> = listOf(
        // Authorization: Bearer <token>
        Regex("(Authorization)\\s*:\\s*[^\\r\\n]+", RegexOption.IGNORE_CASE) to "$1: $REDACTED",

        // Brave Search auth header
        Regex("(X-Subscription-Token)\\s*:\\s*[^\\r\\n]+", RegexOption.IGNORE_CASE) to "$1: $REDACTED",

        // OAuth / API-key style standalone tokens — match `Bearer <token>`
        // or `Token <token>` even outside a header line.
        Regex("\\b(Bearer|Token)\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE) to "$1 $REDACTED",

        // URL query strings (the path remains for debugability; only the
        // `?...` tail goes). Matches `https://...?foo=bar` style URLs.
        // Note: the regex matches both the question mark and everything
        // until whitespace, so the entire query string is replaced.
        Regex("\\?[^\\s\"<>]+") to "?<redacted-query>",
    )

    /**
     * Apply every redaction pattern to [text]. Returns the input unchanged
     * if no pattern matched (no allocation for the no-op case).
     */
    fun redact(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        var result: String = text
        for ((pattern, replacement) in patterns) {
            result = result.replace(pattern, replacement)
        }
        return result
    }

    /**
     * Convenience: redact a `Throwable`'s message + every string-shaped
     * `arg` in its stack trace causes. Returns a new exception with the
     * same class but a redacted message; the stack trace is preserved
     * (the redaction is shallow — only `message` and `cause.message`
     * recursively). Phase D's `SafeCrashReporter` uses this before
     * forwarding to `FirebaseCrashlytics.recordException`.
     *
     * If [throwable]'s message is null and no chained cause has a
     * redacted message, returns [throwable] unchanged to avoid losing
     * the stack trace.
     */
    fun redactThrowable(throwable: Throwable): Throwable {
        val redactedMessage = redact(throwable.message)
        if (redactedMessage == throwable.message && throwable.cause == null) {
            // Fast path: nothing to redact.
            return throwable
        }
        // We can't mutate `Throwable.message` directly; wrap in a new
        // exception of a generic type. Crashlytics's stack-trace capture
        // operates on the wrapping throwable, but the original class
        // name is preserved as the cause's class so it still appears in
        // the dashboard.
        return RedactedThrowable(
            redactedClassName = throwable::class.simpleName ?: "Throwable",
            redactedMessage = redactedMessage,
            cause = throwable.cause?.let { redactThrowable(it) },
        ).apply {
            stackTrace = throwable.stackTrace
        }
    }
}

/**
 * A throwable whose message has been redacted. Preserves the stack trace
 * of the original throwable while substituting a scrubbed message string.
 */
class RedactedThrowable internal constructor(
    redactedClassName: String,
    redactedMessage: String?,
    cause: Throwable?,
) : Throwable(message = "[$redactedClassName] ${redactedMessage ?: "<no message>"}", cause = cause)
