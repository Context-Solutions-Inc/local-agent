package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSubtypeDetectorTest {

    private val detector = SearchSubtypeDetector()

    @Test
    fun weather_queries_route_to_WEATHER() {
        assertEquals(SearchSubtype.WEATHER, detector.detect("what's the weather in Toronto"))
        assertEquals(SearchSubtype.WEATHER, detector.detect("Toronto forecast for tomorrow"))
        assertEquals(SearchSubtype.WEATHER, detector.detect("is it raining in Vancouver"))
        assertEquals(SearchSubtype.WEATHER, detector.detect("temperature in New York right now"))
        assertEquals(SearchSubtype.WEATHER, detector.detect("how's the humidity today"))
        assertEquals(SearchSubtype.WEATHER, detector.detect("UV index for the weekend"))
    }

    @Test
    fun sports_queries_route_to_SPORTS() {
        assertEquals(SearchSubtype.SPORTS, detector.detect("Leafs score last night"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("NHL standings"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("who won the Super Bowl"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("who won the masters last year"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("Premier League fixtures this week"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("Raptors game tonight"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("Champions League playoff bracket"))
    }

    @Test
    fun finance_queries_route_to_FINANCE() {
        // Macro / market-wide / crypto / forex vocabulary — not a single
        // instrument, so these stay on the FINANCE (market-news) vertical.
        assertEquals(SearchSubtype.FINANCE, detector.detect("S&P 500 today"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("TSX close yesterday"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("bitcoin price"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("EUR/USD exchange rate"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("interest rate decision"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("inflation report"))
    }

    @Test
    fun single_instrument_queries_route_to_STOCKS() {
        // Checked before FINANCE: a specific stock/ETF price or quote intent
        // routes to the ticker resolver, not the market-news feed.
        assertEquals(SearchSubtype.STOCKS, detector.detect("AAPL stock price"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("what's nvidia's stock price"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("tsla share price"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("price of apple shares"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("tesla stock"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("what's the market cap of nvidia"))
        assertEquals(SearchSubtype.STOCKS, detector.detect("\$NVDA earnings call"))
    }

    @Test
    fun market_wide_stock_phrases_stay_FINANCE_not_STOCKS() {
        // "stock market / exchange" and queries that *start* with "stock"
        // (no preceding company entity) must not be captured by STOCKS.
        assertEquals(SearchSubtype.FINANCE, detector.detect("stock market today"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("the stock market is down"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("how is the stock exchange doing"))
    }

    @Test
    fun news_queries_route_to_NEWS() {
        assertEquals(SearchSubtype.NEWS, detector.detect("latest news on the election"))
        assertEquals(SearchSubtype.NEWS, detector.detect("breaking news in Canada"))
        assertEquals(SearchSubtype.NEWS, detector.detect("headlines this morning"))
        assertEquals(SearchSubtype.NEWS, detector.detect("trending stories today"))
        assertEquals(SearchSubtype.NEWS, detector.detect("update on the trade deal"))
    }

    @Test
    fun ambiguous_queries_fall_back_to_GENERAL() {
        assertEquals(SearchSubtype.GENERAL, detector.detect("who is the prime minister of canada"))
        assertEquals(SearchSubtype.GENERAL, detector.detect("best restaurants near me"))
        assertEquals(SearchSubtype.GENERAL, detector.detect("how to fix a leaky faucet"))
        assertEquals(SearchSubtype.GENERAL, detector.detect("history of the eiffel tower"))
        assertEquals(SearchSubtype.GENERAL, detector.detect("recipe for sourdough bread"))
    }

    @Test
    fun finance_keyword_wins_over_news_for_market_news() {
        // FINANCE checked before NEWS — "stock market news" routes to FINANCE
        // because the ticker/market vocabulary is stronger signal.
        assertEquals(SearchSubtype.FINANCE, detector.detect("stock market news today"))
    }

    @Test
    fun weather_keyword_wins_over_general_even_with_question_words() {
        assertEquals(SearchSubtype.WEATHER, detector.detect("how cold is the weather today in Chicago"))
    }

    @Test
    fun bare_ticker_without_finance_keyword_does_not_misroute() {
        // $TICKER pattern requires the dollar prefix; bare uppercase acronyms
        // like "CEO" / "ATM" must not trigger FINANCE.
        assertEquals(SearchSubtype.GENERAL, detector.detect("what does CEO stand for"))
        assertEquals(SearchSubtype.GENERAL, detector.detect("ATM near me"))
    }
}
