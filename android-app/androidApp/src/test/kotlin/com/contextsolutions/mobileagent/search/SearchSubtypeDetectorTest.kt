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
        assertEquals(SearchSubtype.SPORTS, detector.detect("Premier League fixtures this week"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("Raptors game tonight"))
        assertEquals(SearchSubtype.SPORTS, detector.detect("Champions League playoff bracket"))
    }

    @Test
    fun finance_queries_route_to_FINANCE() {
        assertEquals(SearchSubtype.FINANCE, detector.detect("AAPL stock price"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("what's the market cap of nvidia"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("S&P 500 today"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("TSX close yesterday"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("\$NVDA earnings call"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("bitcoin price"))
        assertEquals(SearchSubtype.FINANCE, detector.detect("EUR/USD exchange rate"))
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
