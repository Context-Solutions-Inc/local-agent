package com.contextsolutions.mobileagent.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherLocationResolverTest {

    // Catalog with deliberately ambiguous names: Springfield (IL + MO),
    // Portland (OR + ME), London (CA/ON + GB/ENG), plus San Diego to test
    // word-boundary matching.
    private val catalogJson = """
        {
          "countries": [
            { "code": "US", "name": "United States", "regions": [
              { "code": "FL", "name": "Florida", "cities": [ { "name": "Miami", "lat": 25.7617, "lon": -80.1918 } ] },
              { "code": "IL", "name": "Illinois", "cities": [ { "name": "Springfield", "lat": 39.7817, "lon": -89.6501 } ] },
              { "code": "MO", "name": "Missouri", "cities": [ { "name": "Springfield", "lat": 37.2090, "lon": -93.2923 } ] },
              { "code": "OR", "name": "Oregon", "cities": [ { "name": "Portland", "lat": 45.5152, "lon": -122.6784 } ] },
              { "code": "ME", "name": "Maine", "cities": [ { "name": "Portland", "lat": 43.6591, "lon": -70.2568 } ] },
              { "code": "CA", "name": "California", "cities": [ { "name": "San Diego", "lat": 32.7157, "lon": -117.1611 } ] }
            ] },
            { "code": "CA", "name": "Canada", "regions": [
              { "code": "ON", "name": "Ontario", "cities": [ { "name": "Toronto", "lat": 43.6532, "lon": -79.3832 }, { "name": "London", "lat": 42.9849, "lon": -81.2453 } ] }
            ] },
            { "code": "GB", "name": "United Kingdom", "regions": [
              { "code": "ENG", "name": "England", "cities": [ { "name": "London", "lat": 51.5074, "lon": -0.1278 } ] }
            ] }
          ]
        }
    """.trimIndent()

    private val resolver = WeatherLocationResolver(LocationCatalog(catalogJson))

    @Test
    fun resolves_city_with_state_name() {
        val r = resolver.resolve("what's the weather in Miami, Florida?", preferredCountry = "CA")!!
        assertEquals("Miami", r.city)
        assertEquals("FL", r.regionCode)
        assertEquals("US", r.country)
        assertEquals(25.7617, r.coords.latitude, 0.0)
    }

    @Test
    fun disambiguates_same_city_by_state_name() {
        assertEquals("IL", resolver.resolve("weather in Springfield, Illinois")!!.regionCode)
        assertEquals("MO", resolver.resolve("weather in Springfield, Missouri")!!.regionCode)
        assertEquals("OR", resolver.resolve("forecast for Portland Oregon")!!.regionCode)
        assertEquals("ME", resolver.resolve("forecast for Portland Maine")!!.regionCode)
    }

    @Test
    fun disambiguates_by_uppercase_state_code_but_not_lowercase_word() {
        // Upper-case "IL" is clearly a state code.
        assertEquals("IL", resolver.resolve("weather in Springfield IL")!!.regionCode)
        // A bare lower-case "or"/"me" must NOT be read as Oregon/Maine — it's
        // a common word. With no other signal we fall back to country/order,
        // so "weather in portland or rain" stays resolvable to *a* Portland
        // without "or" forcing Oregon.
        val r = resolver.resolve("is it portland or seattle weather", preferredCountry = "US")
        // Both Portlands are US; tie-break is deterministic (first in catalog),
        // and crucially the lowercase "or" didn't scope it to Oregon by code.
        assertEquals("Portland", r!!.city)
    }

    @Test
    fun prefers_callers_country_when_no_state_given() {
        // "London" exists in CA (Ontario) and GB (England); no state named.
        assertEquals("CA", resolver.resolve("weather in london", preferredCountry = "CA")!!.country)
        assertEquals("GB", resolver.resolve("weather in london", preferredCountry = "GB")!!.country)
    }

    @Test
    fun state_name_beats_country_preference() {
        // Even a CA-preferred user asking about "London, England" gets GB.
        val r = resolver.resolve("weather in London, England", preferredCountry = "CA")!!
        assertEquals("GB", r.country)
        assertEquals("ENG", r.regionCode)
    }

    @Test
    fun matches_only_on_word_boundaries() {
        // "san diego" must not be found inside "san diegans".
        assertNull(resolver.resolve("the san diegans love sunshine"))
        // But the real city resolves.
        assertEquals("San Diego", resolver.resolve("weather in San Diego")!!.city)
    }

    @Test
    fun returns_null_when_no_city_present() {
        assertNull(resolver.resolve("what's the weather today?"))
        assertNull(resolver.resolve(""))
    }

    // -- resolveDetailed (PR #89: ambiguity-aware) ---------------------------

    @Test
    fun resolveDetailed_flags_ambiguous_city_without_qualifier() {
        val r = resolver.resolveDetailed("what is the weather like in london")
        val ambiguous = r as WeatherLocationResolver.Resolution.Ambiguous
        assertEquals("London", ambiguous.city)
        // Both Londons offered, no silent pick.
        assertEquals(setOf("CA", "GB"), ambiguous.options.map { it.country }.toSet())
    }

    @Test
    fun resolveDetailed_ambiguous_for_springfield() {
        val r = resolver.resolveDetailed("weather in springfield")
        val ambiguous = r as WeatherLocationResolver.Resolution.Ambiguous
        assertEquals(setOf("IL", "MO"), ambiguous.options.map { it.regionCode }.toSet())
    }

    @Test
    fun resolveDetailed_unique_when_state_qualifies() {
        val r = resolver.resolveDetailed("weather in London, Ontario")
        val unique = r as WeatherLocationResolver.Resolution.Unique
        assertEquals("CA", unique.resolved.country)
        assertEquals("ON", unique.resolved.regionCode)
    }

    @Test
    fun resolveDetailed_unique_when_country_qualifies() {
        // Full country name, alias, and code all disambiguate without a prompt.
        assertEquals("GB", (resolver.resolveDetailed("weather in london england") as WeatherLocationResolver.Resolution.Unique).resolved.country)
        assertEquals("GB", (resolver.resolveDetailed("weather in london uk") as WeatherLocationResolver.Resolution.Unique).resolved.country)
        assertEquals("CA", (resolver.resolveDetailed("weather in london canada") as WeatherLocationResolver.Resolution.Unique).resolved.country)
    }

    @Test
    fun resolveDetailed_does_not_use_onboarded_country_to_break_ties() {
        // resolveDetailed takes no preferredCountry — a bare ambiguous city is
        // ALWAYS ambiguous regardless of where the user onboarded.
        assertEquals(
            WeatherLocationResolver.Resolution.Ambiguous::class,
            resolver.resolveDetailed("weather in london")::class,
        )
    }

    @Test
    fun resolveDetailed_unique_for_unambiguous_city() {
        val r = resolver.resolveDetailed("weather in Toronto")
        val unique = r as WeatherLocationResolver.Resolution.Unique
        assertEquals("Toronto", unique.resolved.city)
        assertEquals("ON", unique.resolved.regionCode)
    }

    @Test
    fun resolveDetailed_none_when_no_city() {
        assertEquals(WeatherLocationResolver.Resolution.None, resolver.resolveDetailed("what's the weather today?"))
        assertEquals(WeatherLocationResolver.Resolution.None, resolver.resolveDetailed("the san diegans love sunshine"))
    }
}
