package com.contextsolutions.mobileagent.preferences

import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Resolves the bundled `search_defaults.json` asset into a [VerticalPreferences]
 * for a given country code, with a fallback chain:
 *
 *   country → first-listed default in the JSON → empty list
 *
 * The asset is parsed once from a JSON string the Android layer hands in
 * (avoids commonMain having to know how to open Android assets).
 */
class DefaultSiteResolver(jsonText: String) {

    private val parsed: DefaultsFile = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }.decodeFromString(DefaultsFile.serializer(), jsonText)

    fun defaultsFor(country: String): VerticalPreferences {
        val countryEntry = parsed.countries[country.uppercase()] ?: parsed.countries[parsed.fallback]
            ?: return VerticalPreferences()
        return VerticalPreferences(
            general = countryEntry.general,
            news = countryEntry.news,
            weather = countryEntry.weather,
            sports = countryEntry.sports,
            finance = countryEntry.finance,
        )
    }

    /** Whether the file declared a default for [country] (vs. fell back). */
    fun hasExplicitDefault(country: String): Boolean =
        parsed.countries.containsKey(country.uppercase())

    @Serializable
    private data class DefaultsFile(
        val fallback: String,
        val countries: Map<String, CountryDefaults>,
    )

    @Serializable
    private data class CountryDefaults(
        val general: List<SiteConfig> = emptyList(),
        val news: List<SiteConfig> = emptyList(),
        val weather: List<SiteConfig> = emptyList(),
        val sports: List<SiteConfig> = emptyList(),
        val finance: List<SiteConfig> = emptyList(),
    )

    companion object {
        /**
         * Merge [user] preferences on top of [defaults]: any vertical the user
         * has explicitly customised (non-empty list) wins; any vertical they
         * haven't touched (empty list) inherits the defaults. Used when
         * onboarding seeds defaults but the user has already edited Settings.
         */
        fun merge(defaults: VerticalPreferences, user: VerticalPreferences): VerticalPreferences =
            VerticalPreferences(
                general = if (user.general.isNotEmpty()) user.general else defaults.general,
                news = if (user.news.isNotEmpty()) user.news else defaults.news,
                weather = if (user.weather.isNotEmpty()) user.weather else defaults.weather,
                sports = if (user.sports.isNotEmpty()) user.sports else defaults.sports,
                finance = if (user.finance.isNotEmpty()) user.finance else defaults.finance,
            )

        /**
         * Substitute placeholder tokens (`{country}`, `{region}`, `{city}`,
         * `{lat}`, `{lon}`, `{query}`) into [template] from the location +
         * GPS + query context.
         *
         * Returns null when the template references `{lat}` / `{lon}` but
         * [gps] is null — the caller skips this source rather than firing
         * an HTTP request against a partially-substituted URL (which would
         * 404 or return generic content). Other unknown placeholders are
         * left untouched (so a feed URL with literal curly braces in a
         * query string survives).
         *
         * Lat / lon are formatted to ~4 decimal places (matching the
         * catalog's precision); we deliberately don't use the platform's
         * locale-sensitive formatter so the URL works the same on a
         * French locale as on English.
         */
        fun applyPlaceholders(
            template: String,
            location: UserLocation?,
            query: String,
            gps: GpsCoordinates? = null,
        ): String? {
            val needsGps = template.contains("{lat}") || template.contains("{lon}")
            if (needsGps && gps == null) return null
            var out = template
            if (location != null) {
                out = out.replace("{country}", location.country)
                out = out.replace("{region}", location.regionCode)
                out = out.replace("{city}", location.city.replace(" ", "+"))
            }
            if (gps != null) {
                out = out.replace("{lat}", formatCoord(gps.latitude))
                out = out.replace("{lon}", formatCoord(gps.longitude))
            }
            out = out.replace("{query}", query.replace(" ", "+"))
            return out
        }

        /**
         * Locale-independent decimal formatting for URL placeholders.
         * Trims trailing zeros after a sane fixed precision so the URL
         * looks clean ("43.6532,-79.3832" instead of "43.65320000,...").
         */
        private fun formatCoord(value: Double): String {
            // Round to 4 decimals (~11 m at the equator) — matches the
            // precision the catalog stores.
            val rounded = kotlin.math.round(value * 10_000.0) / 10_000.0
            // Use a manual format so we don't depend on the JVM's default
            // locale (commas in fr-FR would break URL parsing).
            val asString = rounded.toString()
            // Kotlin's Double.toString returns "1.0E-4" for tiny values;
            // never hits our coord range so we don't worry about it.
            return asString
        }
    }
}
