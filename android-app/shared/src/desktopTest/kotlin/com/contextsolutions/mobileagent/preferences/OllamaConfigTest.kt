package com.contextsolutions.mobileagent.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PR #56 — [OllamaConfig] URL building, configured-gate, and model selection. */
class OllamaConfigTest {

    @Test
    fun isConfiguredRequiresHostPortAndChatModel() {
        assertFalse(OllamaConfig.EMPTY.isConfigured)
        assertFalse(OllamaConfig(host = "1.2.3.4", port = 11434).isConfigured)
        assertFalse(OllamaConfig(host = "1.2.3.4", chatModel = "m").isConfigured)
        assertTrue(OllamaConfig(host = "1.2.3.4", port = 11434, chatModel = "m").isConfigured)
    }

    @Test
    fun baseUrlPrependsHttpScheme() {
        assertEquals("http://192.168.1.50:11434", OllamaConfig(host = "192.168.1.50", port = 11434).baseUrl())
        assertNull(OllamaConfig(host = "", port = 11434).baseUrl())
        assertNull(OllamaConfig(host = "x", port = null).baseUrl())
    }

    @Test
    fun baseUrlPreservesExplicitScheme() {
        assertEquals("https://ollama.local:443", OllamaConfig(host = "https://ollama.local", port = 443).baseUrl())
    }

    @Test
    fun baseUrlUsesHttpsWhenSslChecked() {
        // PR #73 — the SSL checkbox flips a bare host to https.
        assertEquals(
            "https://192.168.1.50:11434",
            OllamaConfig(host = "192.168.1.50", port = 11434, useSsl = true).baseUrl(),
        )
    }

    @Test
    fun openAiServerUsesBaseUrlVerbatimOverHttpsAndIgnoresPort() {
        // PR #73 — an OpenAI-compatible server takes a full base URL (path preserved),
        // implies SSL even with useSsl=false, and ignores the port field.
        val cfg = OllamaConfig(
            host = "openrouter.ai/api/v1",
            port = 11434,
            serverType = RemoteServerType.OPENAI,
            chatModel = "m",
        )
        assertTrue(cfg.sslEnabled)
        assertTrue(cfg.isConfigured)
        assertEquals("https://openrouter.ai/api/v1", cfg.baseUrl())
    }

    @Test
    fun openAiBaseUrlExplicitHttpSchemeWins() {
        // A local OpenAI server over plain http (e.g. LM Studio) keeps its scheme.
        val cfg = OllamaConfig(
            host = "http://localhost:1234/v1",
            serverType = RemoteServerType.OPENAI,
            chatModel = "m",
        )
        assertEquals("http://localhost:1234/v1", cfg.baseUrl())
    }

    @Test
    fun openAiIsConfiguredWithoutPort() {
        assertTrue(
            OllamaConfig(host = "https://api.openai.com/v1", serverType = RemoteServerType.OPENAI, chatModel = "m")
                .isConfigured,
        )
    }

    @Test
    fun explicitHttpSchemeStillWinsOverSslFlag() {
        // A user-typed scheme is authoritative even if the SSL flag disagrees.
        assertEquals(
            "http://192.168.1.50:11434",
            OllamaConfig(host = "http://192.168.1.50", port = 11434, useSsl = true).baseUrl(),
        )
    }

    @Test
    fun isActiveRequiresConfiguredAndEnabled() {
        // PR #73 — the routing gate. Default enabled=true preserves upgrade behavior.
        val configured = OllamaConfig(host = "1.2.3.4", port = 11434, chatModel = "m")
        assertTrue(configured.enabled)
        assertTrue(configured.isActive)

        // Switched off: still configured (details kept) but not active.
        val off = configured.copy(enabled = false)
        assertTrue(off.isConfigured)
        assertFalse(off.isActive)

        // Enabled but not configured is not active either.
        assertFalse(OllamaConfig(enabled = true).isActive)
    }

    @Test
    fun modelForPicksVisionOnlyForImageTurnsWhenSet() {
        val both = OllamaConfig(host = "h", port = 1, chatModel = "chat", visionModel = "vis")
        assertEquals("chat", both.modelFor(hasImage = false))
        assertEquals("vis", both.modelFor(hasImage = true))

        // Blank vision model → image turns fall back to the chat model.
        val chatOnly = OllamaConfig(host = "h", port = 1, chatModel = "chat")
        assertEquals("chat", chatOnly.modelFor(hasImage = true))
    }
}
