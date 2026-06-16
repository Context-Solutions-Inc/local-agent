package com.contextsolutions.localagent.preferences

/**
 * Resolves a free-text mention of a place ("weather in Miami, Florida",
 * "Toronto Ontario", "what's it like in San Jose?") to a catalog city +
 * GPS coordinates, scanning the bundled [LocationCatalog].
 *
 * Onboarding (PR #37) only captures the user's *country* — the weather path
 * asks the user to name the city + state/province at query time, and this
 * resolver turns that into the `{lat}` / `{lon}` a weather source needs. The
 * city's country is returned too so the caller can pick the right national
 * weather source (NWS for a US city, Environment Canada for a CA city, …)
 * regardless of the onboarded country.
 *
 * Matching is deterministic (no LLM): a catalog city name must appear in the
 * text on word boundaries. Ambiguous names (London ON vs London ENG; the
 * many Springfields; Portland OR vs ME) are disambiguated, in order, by:
 *  1. a state/province *name* present in the text ("Ontario", "Florida"),
 *  2. a state/province *code* present as an upper-case token ("ON", "FL"),
 *  3. the caller's [preferredCountry],
 *  4. the longest (most specific) city-name match, then first-in-catalog.
 */
class WeatherLocationResolver(private val catalog: LocationCatalog) {

    data class Resolved(
        val city: String,
        val regionCode: String,
        val regionName: String,
        val country: String,
        val coords: GpsCoordinates,
    ) {
        /** Same catalog place (city + region + country), case-insensitively. */
        fun samePlaceAs(other: Resolved): Boolean =
            city.equals(other.city, ignoreCase = true) &&
                regionCode.equals(other.regionCode, ignoreCase = true) &&
                country.equals(other.country, ignoreCase = true)
    }

    /**
     * Outcome of [resolveDetailed]. Unlike [resolve] this NEVER silently picks
     * one of several distinct places that share the matched name — the caller
     * decides (disambiguate from a saved location, or prompt). PR #89.
     */
    sealed interface Resolution {
        /** Exactly one catalog place — the text qualified it (region/country) or
         *  only one distinct place carries the most-specific matched name. */
        data class Unique(val resolved: Resolved) : Resolution

        /** The matched city name maps to >1 distinct place and the text gives no
         *  region/country qualifier. [options] are the distinct places, in
         *  catalog order. */
        data class Ambiguous(val city: String, val options: List<Resolved>) : Resolution

        /** No catalog city was named. */
        object None : Resolution
    }

    private data class Candidate(
        val country: String,
        val region: LocationCatalog.RegionEntry,
        val city: LocationCatalog.CityEntry,
        val matchIndex: Int,
    ) {
        fun toResolved(): Resolved = Resolved(
            city = city.name,
            regionCode = region.code,
            regionName = region.name,
            country = country,
            coords = GpsCoordinates(city.lat, city.lon),
        )
    }

    fun resolve(text: String, preferredCountry: String? = null): Resolved? {
        if (text.isBlank()) return null
        val lower = text.lowercase()

        val candidates = buildList {
            for (country in catalog.countries()) {
                for (region in country.regions) {
                    for (city in region.cities) {
                        val idx = boundedIndexOf(lower, city.name.lowercase())
                        if (idx >= 0) add(Candidate(country.code, region, city, idx))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null

        // 1 + 2: disambiguate by an explicit state/province in the text.
        val regionScoped = candidates.filter { c ->
            boundedIndexOf(lower, c.region.name.lowercase()) >= 0 ||
                hasUpperCaseToken(text, c.region.code)
        }
        var pool = regionScoped.ifEmpty { candidates }

        // 3: prefer the caller's country when the region didn't narrow it.
        if (regionScoped.isEmpty() && preferredCountry != null) {
            val countryScoped = pool.filter { it.country.equals(preferredCountry, ignoreCase = true) }
            if (countryScoped.isNotEmpty()) pool = countryScoped
        }

        // 4: most specific (longest) city name wins; tie-break earliest mention.
        val best = pool.maxWithOrNull(
            compareBy<Candidate> { it.city.name.length }.thenByDescending { it.matchIndex },
        ) ?: return null

        return Resolved(
            city = best.city.name,
            regionCode = best.region.code,
            regionName = best.region.name,
            country = best.country,
            coords = GpsCoordinates(best.city.lat, best.city.lon),
        )
    }

    /**
     * Like [resolve], but reports AMBIGUITY instead of silently guessing. Used
     * by the weather path (PR #89) for a city the user typed: when the name maps
     * to more than one catalog place (London ON vs London England) and the text
     * carries no region/country qualifier, the caller disambiguates from a saved
     * location or prompts — it must not assume. The onboarded country is NOT a
     * tie-breaker here (it names a country, not which London the user meant).
     */
    fun resolveDetailed(text: String): Resolution {
        if (text.isBlank()) return Resolution.None
        val lower = text.lowercase()

        val candidates = buildList {
            for (country in catalog.countries()) {
                for (region in country.regions) {
                    for (city in region.cities) {
                        val idx = boundedIndexOf(lower, city.name.lowercase())
                        if (idx >= 0) add(Candidate(country.code, region, city, idx))
                    }
                }
            }
        }
        if (candidates.isEmpty()) return Resolution.None

        // An explicit region (name/code) or country (name/alias/code) in the
        // text uniquely qualifies the place — fall back to resolve()'s ordering.
        val regionScoped = candidates.filter { c ->
            boundedIndexOf(lower, c.region.name.lowercase()) >= 0 ||
                hasUpperCaseToken(text, c.region.code)
        }
        val qualified = regionScoped.ifEmpty {
            candidates.filter { c -> textNamesCountry(lower, text, c.country) }
        }
        if (qualified.isNotEmpty()) {
            val best = qualified.maxWithOrNull(
                compareBy<Candidate> { it.city.name.length }.thenByDescending { it.matchIndex },
            ) ?: return Resolution.None
            return Resolution.Unique(best.toResolved())
        }

        // No qualifier: keep only the most-specific matched name ("New York"
        // beats "York"), then look at how many DISTINCT places remain.
        val maxLen = candidates.maxOf { it.city.name.length }
        val distinct = candidates
            .filter { it.city.name.length == maxLen }
            .map { it.toResolved() }
            .distinctBy { listOf(it.country.lowercase(), it.regionCode.lowercase(), it.city.lowercase()) }
        return if (distinct.size == 1) {
            Resolution.Unique(distinct.single())
        } else {
            Resolution.Ambiguous(city = distinct.first().city, options = distinct)
        }
    }

    /** True when [text] explicitly names [countryCode] — the catalog country
     *  name ("United Kingdom"), a common alias ("uk", "britain", "usa"), or the
     *  upper-case code ("GB"). Lets "weather in London, UK" resolve without a
     *  prompt. */
    private fun textNamesCountry(lower: String, original: String, countryCode: String): Boolean {
        val name = catalog.country(countryCode)?.name?.lowercase()
        if (name != null && boundedIndexOf(lower, name) >= 0) return true
        if (hasUpperCaseToken(original, countryCode)) return true
        return COUNTRY_ALIASES[countryCode.uppercase()].orEmpty().any { boundedIndexOf(lower, it) >= 0 }
    }

    /**
     * Index of [needle] in [haystack] only when it sits on word boundaries
     * (so "york" inside "yorkshire" or "san jose" inside "san josefina"
     * doesn't match), else -1. Both args are already lower-cased by the
     * caller for the city/region-name scans; the region-code scan uses
     * [hasUpperCaseToken] instead.
     */
    private fun boundedIndexOf(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return -1
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return -1
            val before = i - 1
            val after = i + needle.length
            val okBefore = before < 0 || !haystack[before].isLetterOrDigit()
            val okAfter = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (okBefore && okAfter) return i
            from = i + 1
        }
    }

    /**
     * True when [code] appears in [original] as a standalone upper-case token
     * ("Springfield, IL"). Case-sensitive on purpose: lower-casing would make
     * 2-letter codes collide with common words ("or" → OR/Oregon, "in" →
     * IN/Indiana, "me" → ME/Maine), so we only honour a code the user clearly
     * typed as a code.
     */
    private fun hasUpperCaseToken(original: String, code: String): Boolean {
        if (code.isBlank()) return false
        var from = 0
        while (true) {
            val i = original.indexOf(code, from)
            if (i < 0) return false
            val before = i - 1
            val after = i + code.length
            val okBefore = before < 0 || !original[before].isLetterOrDigit()
            val okAfter = after >= original.length || !original[after].isLetterOrDigit()
            if (okBefore && okAfter) return true
            from = i + 1
        }
    }

    private companion object {
        // Common spoken/written country qualifiers → ISO code. The catalog's
        // full country name is matched separately; these cover the short forms.
        val COUNTRY_ALIASES: Map<String, List<String>> = mapOf(
            "GB" to listOf("uk", "britain", "great britain", "england", "scotland", "wales"),
            "US" to listOf("usa", "america", "united states"),
            "CA" to listOf("canada"),
            "AU" to listOf("australia", "aus"),
        )
    }
}
