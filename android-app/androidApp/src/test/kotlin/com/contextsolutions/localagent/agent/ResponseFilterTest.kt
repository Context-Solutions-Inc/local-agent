package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.language.PreferredLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ResponseFilterTest {

    @Test
    fun `NoOp returns input verbatim`() {
        val text = "These laws describe how 物体 (objects) behave"
        assertEquals(text, ResponseFilter.NoOp.filter(text))
    }

    @Test
    fun `EN filter keeps Latin text unchanged and avoids reallocation`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        val text = "Hello, world! 42 + 5 = 47. café résumé"
        val result = filter.filter(text)
        // Fast-path returns the original reference when nothing's stripped.
        assertSame("fast-path should return the same instance", text, result)
    }

    @Test
    fun `EN filter strips CJK and reports the cleaned string`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        // The bug from the user's report — Galileo physics response.
        val raw = "These laws describe how 物体 (objects) behave when subjected to forces."
        val expected = "These laws describe how  (objects) behave when subjected to forces."
        assertEquals(expected, filter.filter(raw))
    }

    @Test
    fun `EN filter strips a single trailing CJK character`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        assertEquals("Galileo studied motion of objects", filter.filter("Galileo studied motion of objects物"))
    }

    @Test
    fun `EN filter strips Cyrillic and Hangul mid-response`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        assertEquals(
            "one of  most influential scientists",
            filter.filter("one of 世界 most influential scientists"),
        )
        assertEquals("hello  world", filter.filter("hello Я world"))
        assertEquals("hello  world", filter.filter("hello 한 world"))
    }

    @Test
    fun `EN filter keeps Greek and math symbols`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        val text = "When Δ approaches 0, π ≈ 3.14159"
        assertEquals(text, filter.filter(text))
    }

    @Test
    fun `JA filter keeps Japanese and Latin together`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.JA)
        val text = "ありがとう, that's helpful. 物体 means object."
        assertEquals(text, filter.filter(text))
    }

    @Test
    fun `JA filter strips Cyrillic but keeps Japanese`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.JA)
        assertEquals(
            "ありがとう, that means ",
            filter.filter("ありがとう, that means спасибо"),
        )
    }

    @Test
    fun `ZH filter strips Hiragana but keeps Han ideographs`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.ZH)
        assertEquals("你好,  means hello", filter.filter("你好, あ means hello"))
    }

    @Test
    fun `RU filter keeps Cyrillic and Latin`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.RU)
        val text = "Привет world — 5 апреля"
        assertEquals(text, filter.filter(text))
    }

    @Test
    fun `empty string passes through both filters`() {
        assertEquals("", ResponseFilter.NoOp.filter(""))
        assertEquals("", ResponseFilter.allowedScripts(PreferredLanguage.EN).filter(""))
    }

    @Test
    fun `whitespace and newlines are preserved`() {
        val filter = ResponseFilter.allowedScripts(PreferredLanguage.EN)
        val text = "Line one\nLine two\tindented"
        assertEquals(text, filter.filter(text))
    }
}
