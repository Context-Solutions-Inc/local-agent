package com.contextsolutions.mobileagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaToolCallBodyParserTest {

    @Test
    fun `single string argument`() {
        val r = GemmaToolCallBodyParser.parse("""call:web_search{query: "weather in Toronto right now"}""")!!
        assertEquals("web_search", r.name)
        assertEquals("""{"query":"weather in Toronto right now"}""", r.argumentsJson)
    }

    @Test
    fun `multiple arguments mixed types`() {
        val r = GemmaToolCallBodyParser.parse(
            """call:web_search{query: "eagles", count: 5, fresh: true}""",
        )!!
        assertEquals("web_search", r.name)
        assertEquals("""{"query":"eagles","count":5,"fresh":true}""", r.argumentsJson)
    }

    @Test
    fun `tolerates whitespace and surrounding newlines`() {
        val r = GemmaToolCallBodyParser.parse(
            "\n  call : web_search { query :  \"x\" }  \n",
        )!!
        assertEquals("web_search", r.name)
        assertEquals("""{"query":"x"}""", r.argumentsJson)
    }

    @Test
    fun `empty argument list yields empty object`() {
        val r = GemmaToolCallBodyParser.parse("call:noop{}")!!
        assertEquals("noop", r.name)
        assertEquals("{}", r.argumentsJson)
    }

    @Test
    fun `escaped quotes in string value preserved`() {
        val r = GemmaToolCallBodyParser.parse(
            """call:web_search{query: "she said \"hi\""}""",
        )!!
        assertEquals("""{"query":"she said \"hi\""}""", r.argumentsJson)
    }

    @Test
    fun `null and false literals`() {
        val r = GemmaToolCallBodyParser.parse("call:t{a: null, b: false}")!!
        assertEquals("""{"a":null,"b":false}""", r.argumentsJson)
    }

    @Test
    fun `negative and decimal numbers`() {
        val r = GemmaToolCallBodyParser.parse("call:t{lat: -43.65, lon: -79.38, n: 3}")!!
        assertEquals("""{"lat":-43.65,"lon":-79.38,"n":3}""", r.argumentsJson)
    }

    @Test
    fun `body that is not call-prefixed returns null`() {
        assertNull(GemmaToolCallBodyParser.parse("""{"name":"web_search"}"""))
        assertNull(GemmaToolCallBodyParser.parse(""))
        assertNull(GemmaToolCallBodyParser.parse("just some text"))
    }

    @Test
    fun `body with non-empty unparseable args returns null`() {
        // Args present but neither key:value pairs nor literals — fail loudly so
        // the marker parser falls back to text rather than emitting a half-baked call.
        assertNull(GemmaToolCallBodyParser.parse("call:t{just garbage here}"))
    }

    @Test
    fun `name must be a valid identifier`() {
        assertNull(GemmaToolCallBodyParser.parse("""call:123abc{x: "y"}"""))
        assertNull(GemmaToolCallBodyParser.parse("""call:{x: "y"}"""))
    }
}
