package com.contextsolutions.mobileagent.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCacheKeyTest {

    @Test
    fun `lowercases the entire query`() {
        assertEquals("eagles win", SearchCacheKey.normalize("Eagles WIN"))
    }

    @Test
    fun `collapses runs of whitespace and trims edges`() {
        assertEquals("eagles win last night", SearchCacheKey.normalize("  Eagles   win\tlast \n night  "))
    }

    @Test
    fun `preserves punctuation and digits`() {
        assertEquals("aapl @ market close 2026", SearchCacheKey.normalize("AAPL @ market close 2026"))
    }
}
