package com.contextsolutions.mobileagent.search.vertical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlReadabilityExtractorTest {

    private val extractor = HtmlReadabilityExtractor()

    @Test
    fun strips_script_and_style_blocks() {
        val html = """
            <html><head>
              <script>var x = 1;</script>
              <style>p { color: red; }</style>
            </head><body>
              <p>The forecast for today calls for sunny skies and a high of 22.</p>
              <p>Tonight will be partly cloudy with a low of 14.</p>
            </body></html>
        """.trimIndent()
        val text = extractor.extract(html)
        assertFalse("script body should be stripped", text.contains("var x"))
        assertFalse("style body should be stripped", text.contains("color: red"))
        assertTrue("forecast paragraph preserved", text.contains("sunny skies"))
        assertTrue("second paragraph preserved", text.contains("partly cloudy"))
    }

    @Test
    fun scopes_to_article_block_when_present() {
        val html = """
            <html><body>
              <nav><p>Navigation link nobody cares about that is long enough to be picked.</p></nav>
              <article>
                <p>The actual article content begins here and runs for several sentences.</p>
                <p>Another meaningful paragraph inside the article block.</p>
              </article>
              <footer><p>Footer text that is also long enough to pass the threshold.</p></footer>
            </body></html>
        """.trimIndent()
        val text = extractor.extract(html)
        assertTrue("article content present", text.contains("actual article content"))
        assertFalse("nav text excluded", text.contains("Navigation link"))
        assertFalse("footer text excluded", text.contains("Footer text"))
    }

    @Test
    fun decodes_common_html_entities() {
        val html = "<p>This &amp; that with &quot;quotes&quot; and apostrophes&#39;s are decoded properly.</p>"
        val text = extractor.extract(html)
        assertTrue(text.contains("This & that"))
        assertTrue(text.contains("\"quotes\""))
        assertTrue(text.contains("apostrophes's"))
    }

    @Test
    fun caps_output_at_configured_length() {
        val long = (1..200).joinToString(separator = "") { "<p>Paragraph $it with some long enough content to keep it.</p>" }
        val capped = HtmlReadabilityExtractor(maxChars = 500).extract(long)
        assertTrue("output exceeds cap: ${capped.length}", capped.length <= 510) // ellipsis tolerance
    }

    @Test
    fun falls_back_to_tag_strip_when_no_paragraphs_present() {
        // Single div with text, no <p> / <li> — make sure we don't return ""
        val html = "<html><body><div>Just some text without paragraphs.</div></body></html>"
        val text = extractor.extract(html)
        assertTrue("fallback returns text", text.contains("Just some text without paragraphs"))
    }

    @Test
    fun preserves_img_alt_text() {
        // Mirrors weather.gc.ca's wxlink iframe shape: conditions are
        // carried by the alt attribute of an icon image, not as text in
        // any <p>. Without alt preservation, "Mainly Sunny" would be lost.
        val html = """
            <html><body>
              <div class="currentCondImg">
                <img src="/weathericons/01.gif" alt="Mainly Sunny" title="Mainly Sunny">
              </div>
              <div class="currentCondTemp">31°C</div>
              <div class="forecastBox">
                <img src="/weathericons/small/09.png" alt="Chance of showers">
                <div>Tonight 18°C</div>
              </div>
            </body></html>
        """.trimIndent()
        val text = extractor.extract(html)
        assertTrue("current conditions alt missing: $text", text.contains("Mainly Sunny"))
        assertTrue("forecast conditions alt missing: $text", text.contains("Chance of showers"))
        assertTrue("temp text preserved", text.contains("31°C"))
        // The <img> tag itself must NOT survive in the output — only the alt content.
        assertFalse("raw img tag should be stripped", text.contains("<img"))
    }
}
