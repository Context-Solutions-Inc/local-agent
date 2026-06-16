package com.contextsolutions.localagent.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D — covers every redaction pattern + the no-op pass-through.
 *
 * Two failure modes the patterns exist to prevent:
 *  - Leaking the Brave API key (in headers or freeform `Bearer X`
 *    messages).
 *  - Leaking search query content via `?q=...` URL strings ending up in
 *    exception messages or HTTP logs.
 *
 * Note: the redactor does NOT attempt to detect arbitrary "this looks
 * like user content" strings — that's the call-site's responsibility
 * (never put user text into exception messages). The redactor is the
 * fallback for known shapes.
 */
class ContentRedactorTest {

    @Test
    fun redacts_authorization_header() {
        val redacted = ContentRedactor.redact("Authorization: Bearer abc123def456")
        assertEquals("Authorization: <redacted>", redacted)
    }

    @Test
    fun redacts_authorization_case_insensitive() {
        val redacted = ContentRedactor.redact("authorization: Bearer xyz")
        assertEquals("authorization: <redacted>", redacted)
    }

    @Test
    fun redacts_brave_subscription_token_header() {
        val redacted = ContentRedactor.redact("X-Subscription-Token: BSA1234567890abcdef")
        assertEquals("X-Subscription-Token: <redacted>", redacted)
    }

    @Test
    fun redacts_bare_bearer_token() {
        val redacted = ContentRedactor.redact("Failed call with Bearer abc.def-ghi_123 token")
        assertEquals("Failed call with Bearer <redacted> token", redacted)
    }

    @Test
    fun redacts_url_query_string() {
        val redacted = ContentRedactor.redact("GET https://example.com/search?q=secret&foo=bar HTTP/1.1")
        assertEquals("GET https://example.com/search?<redacted-query> HTTP/1.1", redacted)
    }

    @Test
    fun redacts_brave_search_url() {
        val redacted = ContentRedactor.redact(
            "https://api.search.brave.com/res/v1/web/search?q=did+the+eagles+win",
        )
        assertEquals("https://api.search.brave.com/res/v1/web/search?<redacted-query>", redacted)
    }

    @Test
    fun multi_pattern_redaction_in_one_string() {
        // A log line that mentions both an auth header and a URL query.
        val raw = "POST https://api.example.com/?token=xyz with Authorization: Bearer key123"
        val redacted = ContentRedactor.redact(raw)
        assertNotNull(redacted)
        assertFalse("query leaked: $redacted", redacted!!.contains("token=xyz"))
        assertFalse("auth leaked: $redacted", redacted.contains("key123"))
        assertTrue("expected query placeholder", redacted.contains("?<redacted-query>"))
        assertTrue("expected auth placeholder", redacted.contains("Authorization: <redacted>"))
    }

    @Test
    fun pass_through_when_no_secret_shape_present() {
        val text = "Connection timed out after 10s"
        // Implementation does not allocate for no-op replacements; assert
        // the result equals the input.
        assertEquals(text, ContentRedactor.redact(text))
    }

    @Test
    fun null_input_returns_null() {
        assertNull(ContentRedactor.redact(null))
    }

    @Test
    fun empty_input_returns_empty() {
        assertEquals("", ContentRedactor.redact(""))
    }

    @Test
    fun redact_throwable_returns_same_instance_when_message_has_no_secrets() {
        val t = IllegalStateException("model load failed: timeout")
        val redacted = ContentRedactor.redactThrowable(t)
        assertSame(t, redacted)
    }

    @Test
    fun redact_throwable_scrubs_message_with_bearer_token() {
        val t = IllegalStateException("upload failed with Bearer abc123secret token")
        val redacted = ContentRedactor.redactThrowable(t)
        assertNotNull(redacted.message)
        assertFalse(
            "bearer token leaked into redacted throwable: ${redacted.message}",
            redacted.message!!.contains("abc123secret"),
        )
        assertTrue(
            "expected redacted placeholder, got: ${redacted.message}",
            redacted.message!!.contains("Bearer <redacted>"),
        )
    }

    @Test
    fun redact_throwable_preserves_stack_trace() {
        val t = IllegalStateException("Authorization: Bearer secretkey")
        val redacted = ContentRedactor.redactThrowable(t)
        assertEquals(
            "stack trace must be preserved across redaction",
            t.stackTrace.toList(),
            redacted.stackTrace.toList(),
        )
    }

    @Test
    fun redact_throwable_preserves_class_name_in_redacted_message() {
        val t = IllegalStateException("Authorization: Bearer leak")
        val redacted = ContentRedactor.redactThrowable(t)
        // The original class is encoded in the wrapping throwable's message
        // so the Crashlytics dashboard still surfaces what kind of
        // exception was raised.
        assertTrue(
            "class name missing from message: ${redacted.message}",
            redacted.message!!.contains("IllegalStateException"),
        )
    }

    @Test
    fun redact_throwable_chains_through_cause() {
        val cause = IllegalArgumentException("X-Subscription-Token: BSA-key")
        val outer = RuntimeException("wrapping error", cause)
        val redacted = ContentRedactor.redactThrowable(outer)
        val redactedCause = redacted.cause
        assertNotNull("cause must be preserved", redactedCause)
        assertFalse(
            "cause message leaked: ${redactedCause!!.message}",
            redactedCause.message!!.contains("BSA-key"),
        )
    }
}
