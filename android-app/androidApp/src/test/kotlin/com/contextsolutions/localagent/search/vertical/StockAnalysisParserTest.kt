package com.contextsolutions.localagent.search.vertical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockAnalysisParserTest {

    private val parser = StockAnalysisParser()

    // The real page minifies the data blob onto one line (no spaces/newlines
    // between keys) — keep the fixture faithful so the parser's comma anchors
    // are exercised as in production.
    private val nvdaHtml =
        "<html><head><title>NVIDIA (NVDA) Stock Price & Overview</title></head><body><script>" +
            """self.__next={data:{marketCap:"5.31T",marketCapGrowth:91.7,revenue:"253.49B",peRatio:"33.59",forwardPE:"22.07",dividend:"${'$'}0.04 (0.02%)"},""" +
            """quote:{c:-4.16,e:false,h:227.4,l:217.93,o:222.29,p:219.31,u:"May 21, 2026, 11:20 AM EDT",v:101695848,cl:223.47,cp:-1.86,ec:0,ex:"NASDAQ",ms:"open",pd:219.31,td:"2026-05-21",h52:236.54,l52:129.16,uid:"NVDA",days:0,symbol:"NVDA"},state:{ok:true}}""" +
            "</script></body></html>"

    @Test
    fun parses_quote_blob() {
        val q = parser.parse(nvdaHtml)!!
        assertEquals(219.31, q.price, 0.0)
        assertEquals(-4.16, q.change!!, 0.0)
        assertEquals(-1.86, q.changePercent!!, 0.0)
        assertEquals(217.93, q.dayLow!!, 0.0)
        assertEquals(227.4, q.dayHigh!!, 0.0)
        assertEquals(129.16, q.week52Low!!, 0.0)
        assertEquals(236.54, q.week52High!!, 0.0)
        assertEquals(101695848.0, q.volume!!, 0.0)
        assertEquals("NASDAQ", q.exchange)
        assertEquals("May 21, 2026, 11:20 AM EDT", q.asOf)
        assertEquals("5.31T", q.marketCap)
        assertEquals("33.59", q.peRatio)
    }

    @Test
    fun short_keys_do_not_collide_with_suffixed_keys() {
        // `h`/`l` must not pick up `h52`/`l52`, and `c` must not pick up `cp`.
        val q = parser.parse(nvdaHtml)!!
        assertEquals(227.4, q.dayHigh!!, 0.0)   // h, not h52 (236.54)
        assertEquals(217.93, q.dayLow!!, 0.0)   // l, not l52 (129.16)
        assertEquals(-4.16, q.change!!, 0.0)    // c, not cp (-1.86)
    }

    @Test
    fun returns_null_when_no_quote_block() {
        assertNull(parser.parse("<html><body>error page</body></html>"))
        assertNull(parser.parse(""))
    }

    @Test
    fun returns_null_when_price_absent() {
        val noPrice = """<script>quote:{c:-4.16,h:227.4,l:217.93,cp:-1.86}</script>"""
        assertNull(parser.parse(noPrice))
    }
}
