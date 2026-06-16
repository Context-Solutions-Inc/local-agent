package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.language.PreferredLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationIntentDetectorTest {

    private val detector = TranslationIntentDetector()

    // ─── Verb-based triggers ─────────────────────────────────────────────

    @Test
    fun `translate verb triggers intent`() {
        assertTrue(detector.isTranslationRequest("translate hello to French", PreferredLanguage.EN))
    }

    @Test
    fun `translation noun triggers intent`() {
        assertTrue(detector.isTranslationRequest("I need a translation of this poem", PreferredLanguage.EN))
    }

    @Test
    fun `transliterate triggers intent`() {
        assertTrue(detector.isTranslationRequest("Can you transliterate that into the Latin alphabet?", PreferredLanguage.EN))
    }

    @Test
    fun `interpret triggers intent`() {
        assertTrue(detector.isTranslationRequest("How do you interpret this sentence in Japanese?", PreferredLanguage.EN))
    }

    @Test
    fun `case insensitive verb match`() {
        assertTrue(detector.isTranslationRequest("TRANSLATE this to Spanish", PreferredLanguage.EN))
    }

    // ─── Phrase-based triggers ───────────────────────────────────────────

    @Test
    fun `how do you say triggers intent`() {
        assertTrue(detector.isTranslationRequest("How do you say goodbye in Korean", PreferredLanguage.EN))
    }

    @Test
    fun `what does this mean triggers intent`() {
        assertTrue(detector.isTranslationRequest("What does this mean?", PreferredLanguage.EN))
    }

    @Test
    fun `what is the X word for triggers intent`() {
        assertTrue(detector.isTranslationRequest("What is the German word for cat?", PreferredLanguage.EN))
    }

    // ─── Language preposition triggers ───────────────────────────────────

    @Test
    fun `in Japanese triggers intent`() {
        assertTrue(detector.isTranslationRequest("write that in Japanese", PreferredLanguage.EN))
    }

    @Test
    fun `to Spanish triggers intent`() {
        assertTrue(detector.isTranslationRequest("convert to Spanish", PreferredLanguage.EN))
    }

    @Test
    fun `from Chinese triggers intent`() {
        assertTrue(detector.isTranslationRequest("what does this mean from Chinese", PreferredLanguage.EN))
    }

    @Test
    fun `into Russian triggers intent`() {
        assertTrue(detector.isTranslationRequest("put it into Russian please", PreferredLanguage.EN))
    }

    // ─── Cross-script triggers (user typed foreign characters) ───────────

    @Test
    fun `user typed CJK while native is English triggers intent`() {
        assertTrue(detector.isTranslationRequest("what does 你好 mean?", PreferredLanguage.EN))
    }

    @Test
    fun `user typed Latin while native is Japanese triggers intent`() {
        // Latin is ALWAYS allowed (the always-allowed baseline), so a Latin
        // input from a Japanese-native user does NOT trip the cross-script
        // signal alone — it's a normal English question. The other triggers
        // (verb, phrase, preposition) still catch true translation asks.
        assertFalse(
            detector.isTranslationRequest("tell me about Mt Fuji", PreferredLanguage.JA),
        )
    }

    @Test
    fun `user typed Cyrillic while native is Japanese triggers intent`() {
        assertTrue(
            detector.isTranslationRequest("что означает this?", PreferredLanguage.JA),
        )
    }

    // ─── Negative cases ──────────────────────────────────────────────────

    @Test
    fun `plain English question does not trigger intent`() {
        assertFalse(detector.isTranslationRequest("Tell me about Galileo's experiments", PreferredLanguage.EN))
    }

    @Test
    fun `weather question does not trigger intent`() {
        assertFalse(detector.isTranslationRequest("what's the weather", PreferredLanguage.EN))
    }

    @Test
    fun `summary request does not trigger intent`() {
        assertFalse(detector.isTranslationRequest("summarise this article please", PreferredLanguage.EN))
    }

    @Test
    fun `blank input does not trigger intent`() {
        assertFalse(detector.isTranslationRequest("", PreferredLanguage.EN))
        assertFalse(detector.isTranslationRequest("   ", PreferredLanguage.EN))
    }

    @Test
    fun `the word translate inside an unrelated identifier does not match`() {
        // word-boundary regex — "untranslatable" should NOT match "translate"
        // because the word-boundary anchor needs a space/punctuation around it.
        assertFalse(
            "untranslatable should not match",
            detector.isTranslationRequest("This concept is untranslatablefoo here", PreferredLanguage.EN),
        )
    }

    @Test
    fun `Japanese native user asking in Japanese does not trigger intent`() {
        assertFalse(
            detector.isTranslationRequest("こんにちは、富士山について教えて", PreferredLanguage.JA),
        )
    }
}
