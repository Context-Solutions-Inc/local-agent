package com.contextsolutions.localagent.search.vertical

/**
 * Minimal RSS 2.0 / Atom parser. Avoids pulling
 * `ktor-serialization-kotlinx-xml` (which would force every vertical to
 * round-trip through kotlinx.serialization XML — heavier than necessary
 * for the four fields we read).
 *
 * Extracts `<item>` (RSS) or `<entry>` (Atom) and reads `<title>`,
 * `<link>`, `<description>`/`<summary>`, `<pubDate>`/`<published>`. CDATA
 * sections are unwrapped; HTML entities in title/description are decoded
 * by [HtmlReadabilityExtractor]'s entity table.
 */
class RssParser {
    fun parse(xml: String, max: Int = DEFAULT_MAX_ENTRIES): List<RssEntry> {
        val entries = mutableListOf<RssEntry>()
        val nodes = (RSS_ITEM.findAll(xml) + ATOM_ENTRY.findAll(xml))
            .toList()
            .ifEmpty { return emptyList() }
        for (m in nodes) {
            if (entries.size >= max) break
            val body = m.groupValues[1]
            val title = TITLE.find(body)?.let { cleanText(extract(it.groupValues[1])) }.orEmpty()
            val link = LINK_RSS.find(body)?.groupValues?.get(1)?.trim()
                ?: LINK_ATOM_HREF.find(body)?.groupValues?.get(1)?.trim()
                ?: ""
            val description = DESCRIPTION.find(body)?.let { cleanText(extract(it.groupValues[1])) }
                ?: SUMMARY.find(body)?.let { cleanText(extract(it.groupValues[1])) }
                ?: ""
            val pubDate = PUB_DATE.find(body)?.groupValues?.get(1)?.trim()
                ?: PUBLISHED.find(body)?.groupValues?.get(1)?.trim()
            if (title.isBlank() && link.isBlank()) continue
            entries.add(
                RssEntry(
                    title = title,
                    link = link,
                    description = description.take(MAX_DESCRIPTION_CHARS),
                    pubDate = pubDate,
                ),
            )
        }
        return entries
    }

    /** Unwrap a single CDATA section if present, otherwise return as-is. */
    private fun extract(raw: String): String {
        val cdata = CDATA.find(raw)
        return cdata?.groupValues?.get(1) ?: raw
    }

    /**
     * Strip inline HTML tags and decode the most common entities from a
     * <description> / <summary> body. Environment Canada's weather feed
     * (and other feeds wrapping content in CDATA) embeds `<br/>`, `<b>`,
     * and Latin-1 numeric entities — without this pass the snippet ends
     * up with `<b>Condition:</b> Mainly Sunny<br/>` style noise and
     * `&deg;` literals that Gemma has to mentally decode.
     */
    private fun cleanText(raw: String): String =
        raw.replace(TAG, " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&deg;", "°")
            .replace("&#176;", "°")
            .replace("&#8217;", "'")
            .replace("&#8220;", "“")
            .replace("&#8221;", "”")
            .replace(WHITESPACE_RUN, " ")
            .trim()

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 5
        const val MAX_DESCRIPTION_CHARS = 280

        val TAG = Regex("""<[^>]+>""")
        val WHITESPACE_RUN = Regex("""\s+""")
        val RSS_ITEM = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val ATOM_ENTRY = Regex("""<entry\b[^>]*>(.*?)</entry>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val LINK_RSS = Regex("""<link\b[^>/]*>(.*?)</link>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val LINK_ATOM_HREF = Regex("""<link\b[^>]*href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val DESCRIPTION = Regex("""<description\b[^>]*>(.*?)</description>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val SUMMARY = Regex("""<summary\b[^>]*>(.*?)</summary>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val PUB_DATE = Regex("""<pubDate\b[^>]*>(.*?)</pubDate>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val PUBLISHED = Regex("""<published\b[^>]*>(.*?)</published>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val CDATA = Regex("""<!\[CDATA\[(.*?)\]\]>""", RegexOption.DOT_MATCHES_ALL)
    }
}

data class RssEntry(
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String?,
)
