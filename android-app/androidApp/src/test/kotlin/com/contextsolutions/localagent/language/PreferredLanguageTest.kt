package com.contextsolutions.localagent.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates [PreferredLanguage]'s Unicode allow-list — what gets through
 * the streamed-response filter under each native-language setting.
 */
class PreferredLanguageTest {

    @Test
    fun `EN allows Latin letters digits and basic punctuation`() {
        val en = PreferredLanguage.EN
        assertTrue(en.isAllowed('a'.code))
        assertTrue(en.isAllowed('Z'.code))
        assertTrue(en.isAllowed('5'.code))
        assertTrue(en.isAllowed(' '.code))
        assertTrue(en.isAllowed('.'.code))
        assertTrue(en.isAllowed('!'.code))
    }

    @Test
    fun `EN allows extended Latin diacritics and math symbols`() {
        val en = PreferredLanguage.EN
        assertTrue(en.isAllowed('é'.code))     // Latin-1 Supplement
        assertTrue(en.isAllowed('ñ'.code))     // Latin-1 Supplement
        assertTrue(en.isAllowed('Δ'.code))     // Greek (science)
        assertTrue(en.isAllowed('π'.code))     // Greek
        assertTrue(en.isAllowed('±'.code))     // Math (Latin-1)
        assertTrue(en.isAllowed('∞'.code))     // Mathematical Operators
        assertTrue(en.isAllowed('€'.code))     // Currency
    }

    @Test
    fun `EN rejects CJK Hiragana Katakana Hangul Cyrillic`() {
        val en = PreferredLanguage.EN
        assertFalse("CJK rejected", en.isAllowed('物'.code))       // CJK Unified
        assertFalse("CJK rejected", en.isAllowed('体'.code))
        assertFalse("Hiragana rejected", en.isAllowed('あ'.code))   // Hiragana
        assertFalse("Katakana rejected", en.isAllowed('カ'.code))   // Katakana
        assertFalse("Hangul rejected", en.isAllowed('한'.code))     // Hangul Syllables
        assertFalse("Cyrillic rejected", en.isAllowed('Я'.code))   // Cyrillic
    }

    @Test
    fun `JA allows Latin plus CJK plus Hiragana plus Katakana`() {
        val ja = PreferredLanguage.JA
        assertTrue("Latin still allowed", ja.isAllowed('a'.code))
        assertTrue("CJK", ja.isAllowed('物'.code))
        assertTrue("Hiragana", ja.isAllowed('あ'.code))
        assertTrue("Katakana", ja.isAllowed('カ'.code))
    }

    @Test
    fun `JA rejects Cyrillic and Hangul`() {
        val ja = PreferredLanguage.JA
        assertFalse(ja.isAllowed('Я'.code))
        assertFalse(ja.isAllowed('한'.code))
    }

    @Test
    fun `ZH allows CJK but rejects Hiragana Katakana Hangul`() {
        val zh = PreferredLanguage.ZH
        assertTrue(zh.isAllowed('物'.code))
        assertFalse("Hiragana rejected for ZH", zh.isAllowed('あ'.code))
        assertFalse("Katakana rejected for ZH", zh.isAllowed('カ'.code))
        assertFalse("Hangul rejected for ZH", zh.isAllowed('한'.code))
    }

    @Test
    fun `KO allows Hangul plus CJK plus Latin`() {
        val ko = PreferredLanguage.KO
        assertTrue(ko.isAllowed('한'.code))   // Hangul Syllables
        assertTrue(ko.isAllowed('物'.code))   // hanja loans
        assertTrue(ko.isAllowed('a'.code))
        assertFalse(ko.isAllowed('あ'.code))  // not Japanese
    }

    @Test
    fun `RU allows Cyrillic plus Latin`() {
        val ru = PreferredLanguage.RU
        assertTrue(ru.isAllowed('Я'.code))
        assertTrue(ru.isAllowed('a'.code))
        assertFalse(ru.isAllowed('物'.code))
    }

    @Test
    fun `fromCode resolves known codes and falls back to default for unknown`() {
        assertEquals(PreferredLanguage.EN, PreferredLanguage.fromCode("en"))
        assertEquals(PreferredLanguage.JA, PreferredLanguage.fromCode("ja"))
        assertEquals(PreferredLanguage.RU, PreferredLanguage.fromCode("ru"))
        assertEquals(PreferredLanguage.DEFAULT, PreferredLanguage.fromCode(null))
        assertEquals(PreferredLanguage.DEFAULT, PreferredLanguage.fromCode("xx-not-a-real-code"))
    }

    @Test
    fun `DEFAULT is English`() {
        assertEquals(PreferredLanguage.EN, PreferredLanguage.DEFAULT)
    }

    @Test
    fun `Unicode boundary code points are classified correctly`() {
        // Just inside / outside CJK Unified Ideographs (0x4E00..0x9FFF).
        val en = PreferredLanguage.EN
        val ja = PreferredLanguage.JA
        assertFalse("0x4DFF (just below CJK) — EN allows it (math area below 0x4E00)", en.isAllowed(0x4DFF))
        assertFalse("0x4E00 (start of CJK) rejected by EN", en.isAllowed(0x4E00))
        assertFalse("0x9FFF (end of CJK) rejected by EN", en.isAllowed(0x9FFF))
        assertTrue("0x4E00 allowed by JA", ja.isAllowed(0x4E00))
        assertTrue("0x9FFF allowed by JA", ja.isAllowed(0x9FFF))
    }
}
