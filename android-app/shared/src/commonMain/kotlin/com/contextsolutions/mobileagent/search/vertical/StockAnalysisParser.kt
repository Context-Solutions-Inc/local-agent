package com.contextsolutions.mobileagent.search.vertical

/**
 * Extracts the live quote from a stockanalysis.com `/stocks/<ticker>/` page.
 *
 * The page server-renders the quote into a minified JS data blob, e.g.
 * `quote:{c:-4.16,h:227.4,l:217.93,o:222.29,p:219.31,u:"May 21, 2026, 11:20 AM
 * EDT",v:101695848,cl:223.47,cp:-1.86,ex:"NASDAQ",…,h52:236.54,l52:129.16,…}`,
 * plus `marketCap:"5.31T"` and `peRatio:"33.59"` in a sibling object. The
 * current price/change live ONLY in this blob (the visible overview table has
 * Open / Previous Close but not the live price), so we parse it directly.
 *
 * Keys (abbreviated by the site): `p` price, `c` change, `cp` change %, `h`/`l`
 * day high/low, `v` volume, `h52`/`l52` 52-week high/low, `u` update label,
 * `ex` exchange. Best-effort and resilient: a missing/renamed field yields
 * null for that field; only [Quote.price] is required (else parse fails and the
 * caller falls back to web snippets).
 */
class StockAnalysisParser {

    data class Quote(
        val price: Double,
        val change: Double?,
        val changePercent: Double?,
        val dayLow: Double?,
        val dayHigh: Double?,
        val week52Low: Double?,
        val week52High: Double?,
        val volume: Double?,
        val marketCap: String?,
        val peRatio: String?,
        val asOf: String?,
        val exchange: String?,
    )

    fun parse(html: String): Quote? {
        val block = QUOTE_BLOCK.find(html)?.groupValues?.get(1) ?: return null
        val price = block.num("p") ?: return null
        return Quote(
            price = price,
            change = block.num("c"),
            changePercent = block.num("cp"),
            dayLow = block.num("l"),
            dayHigh = block.num("h"),
            week52Low = block.num("l52"),
            week52High = block.num("h52"),
            volume = block.num("v"),
            asOf = block.str("u"),
            exchange = block.str("ex"),
            marketCap = MARKET_CAP.find(html)?.groupValues?.get(1),
            peRatio = PE_RATIO.find(html)?.groupValues?.get(1),
        )
    }

    /** Numeric value for a comma/brace-delimited `key:value` inside the blob. */
    private fun String.num(key: String): Double? =
        Regex("(?:^|,)$key:(-?[0-9]+(?:\\.[0-9]+)?)").find(this)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Quoted-string value for `key:"value"` inside the blob. */
    private fun String.str(key: String): String? =
        Regex("""(?:^|,)$key:"([^"]*)"""").find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private companion object {
        // The quote object is flat (no nested braces), so stop at the first `}`.
        val QUOTE_BLOCK = Regex("""quote:\{([^}]*)\}""")
        val MARKET_CAP = Regex(""""?marketCap"?:"([^"]+)"""")
        val PE_RATIO = Regex(""""?peRatio"?:"([^"]+)"""")
    }
}
