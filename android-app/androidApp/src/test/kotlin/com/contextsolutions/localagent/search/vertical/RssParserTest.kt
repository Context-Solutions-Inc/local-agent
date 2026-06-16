package com.contextsolutions.localagent.search.vertical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    private val parser = RssParser()

    @Test
    fun parses_rss_2_with_cdata_descriptions() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>Example Feed</title>
              <item>
                <title>Leafs beat Habs 4-2</title>
                <link>https://tsn.ca/leafs-habs</link>
                <description><![CDATA[Toronto's late goal sealed the win.]]></description>
                <pubDate>Tue, 13 Jan 2026 02:00:00 GMT</pubDate>
              </item>
              <item>
                <title>Bruins down Rangers</title>
                <link>https://tsn.ca/bruins-rangers</link>
                <description>OT goal from Marchand.</description>
                <pubDate>Tue, 13 Jan 2026 01:30:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent()
        val entries = parser.parse(xml)
        assertEquals(2, entries.size)
        assertEquals("Leafs beat Habs 4-2", entries[0].title)
        assertEquals("https://tsn.ca/leafs-habs", entries[0].link)
        assertTrue(entries[0].description.contains("late goal sealed the win"))
        assertEquals("Tue, 13 Jan 2026 02:00:00 GMT", entries[0].pubDate)
        assertEquals("Bruins down Rangers", entries[1].title)
    }

    @Test
    fun parses_atom_entry_with_href_link() {
        val xml = """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Feed</title>
              <entry>
                <title>Earnings beat expectations</title>
                <link href="https://example.com/earnings"/>
                <summary>Q3 revenue up 18%.</summary>
                <published>2026-04-30T13:00:00Z</published>
              </entry>
            </feed>
        """.trimIndent()
        val entries = parser.parse(xml)
        assertEquals(1, entries.size)
        assertEquals("Earnings beat expectations", entries[0].title)
        assertEquals("https://example.com/earnings", entries[0].link)
        assertEquals("Q3 revenue up 18%.", entries[0].description)
        assertEquals("2026-04-30T13:00:00Z", entries[0].pubDate)
    }

    @Test
    fun caps_results_at_max() {
        val items = (1..10).joinToString(separator = "") {
            "<item><title>Story $it</title><link>https://x.com/$it</link><description>Body.</description></item>"
        }
        val xml = "<rss><channel>$items</channel></rss>"
        val entries = parser.parse(xml, max = 3)
        assertEquals(3, entries.size)
        assertEquals("Story 1", entries[0].title)
        assertEquals("Story 3", entries[2].title)
    }

    @Test
    fun returns_empty_list_for_non_feed_xml() {
        val entries = parser.parse("<html><body>Not a feed</body></html>")
        assertTrue(entries.isEmpty())
    }
}
