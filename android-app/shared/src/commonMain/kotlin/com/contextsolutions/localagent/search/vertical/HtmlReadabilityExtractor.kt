package com.contextsolutions.localagent.search.vertical

/**
 * Minimal hand-rolled content extractor for HTML pages, intended to feed
 * Gemma a paragraph or three of clean text without bringing Jsoup (~1 MB
 * APK, JVM-only) into the build.
 *
 * Strategy (cheap and good-enough):
 *  1. Strip `<script>`, `<style>`, and HTML comments — these poison
 *     downstream regex.
 *  2. If the page has an `<article>` or `<main>` block, scope to that.
 *  3. Pull every `<p>...</p>` (or `<li>...</li>`) inner text, decode the
 *     most common HTML entities, normalise whitespace, and concatenate in
 *     order until the character budget is hit.
 *
 * Not perfect — won't beat Mozilla Readability on weird DOMs — but stable,
 * pure-Kotlin, no native deps, and good enough for current-weather pages
 * and finance summary pages where the meaningful content sits inside
 * `<p>`/`<li>` runs.
 */
class HtmlReadabilityExtractor(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    fun extract(html: String): String {
        var working = html
        working = SCRIPT_BLOCK.replace(working, " ")
        working = STYLE_BLOCK.replace(working, " ")
        working = COMMENT_BLOCK.replace(working, " ")
        // Preserve <img alt="..."> text — weather widgets (notably
        // weather.gc.ca's wxlink iframe) put condition descriptions like
        // "Mainly Sunny" / "Chance of showers" in alt attributes on icon
        // images, with no text label nearby. Stripping the <img> tag
        // outright would drop the only useful condition string on the
        // page. Replacing with " alt-text " before the tag strip pulls
        // those words back into the text stream.
        working = IMG_WITH_ALT.replace(working) { m -> " ${m.groupValues[1]} " }

        val scoped = scopeToArticleOrMain(working)
        val parts = mutableListOf<String>()
        for (m in PARAGRAPH_OR_LIST_ITEM.findAll(scoped)) {
            val text = decodeEntities(stripTags(m.groupValues[1])).trim()
            if (text.length < MIN_PARAGRAPH_CHARS) continue
            parts.add(text)
            if (parts.sumOf { it.length + 1 } > maxChars) break
        }
        if (parts.isEmpty()) {
            // Last-resort fallback: strip all tags + collapse whitespace.
            return decodeEntities(stripTags(scoped))
                .replace(WHITESPACE_RUN, " ")
                .trim()
                .take(maxChars)
        }
        val joined = parts.joinToString(separator = "\n\n")
        return if (joined.length <= maxChars) joined
            else joined.substring(0, maxChars).trimEnd() + "…"
    }

    private fun scopeToArticleOrMain(html: String): String {
        val article = ARTICLE_BLOCK.find(html)
        if (article != null) return article.groupValues[1]
        val main = MAIN_BLOCK.find(html)
        if (main != null) return main.groupValues[1]
        return html
    }

    private fun stripTags(s: String): String = s.replace(TAG, " ").replace(WHITESPACE_RUN, " ")

    private fun decodeEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&#8217;", "'")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")

    private companion object {
        const val DEFAULT_MAX_CHARS = 2400 // ~600 tokens
        const val MIN_PARAGRAPH_CHARS = 30

        // The /s/DOTALL equivalent in Kotlin is RegexOption.DOT_MATCHES_ALL.
        val SCRIPT_BLOCK = Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val STYLE_BLOCK = Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val COMMENT_BLOCK = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        val ARTICLE_BLOCK = Regex("""<article\b[^>]*>(.*?)</article>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val MAIN_BLOCK = Regex("""<main\b[^>]*>(.*?)</main>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val PARAGRAPH_OR_LIST_ITEM = Regex("""<(?:p|li)\b[^>]*>(.*?)</(?:p|li)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        // Captures the alt attribute of an <img>. Non-greedy on the value
        // so adjacent attributes don't get swept in. Quotes are required;
        // unquoted-attr forms aren't worth the parser complexity for the
        // 0.1% of pages that use them.
        val IMG_WITH_ALT = Regex("""<img\b[^>]*\balt\s*=\s*"([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
        val TAG = Regex("""<[^>]+>""")
        val WHITESPACE_RUN = Regex("""\s+""")
    }
}
