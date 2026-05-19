package com.contextsolutions.mobileagent.preferences

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bundled catalog of countries → regions → cities used by the onboarding
 * location picker. Trimmed to a manageable set of common destinations
 * (PR #23 ships with US/CA/GB/AU + ~10 regions each + a few cities) — a
 * full dataset can drop in by updating the JSON asset without changing
 * Kotlin.
 *
 * Each city carries an inline `lat` / `lon` (centroid / city hall, to ~4
 * decimal places). Weather / sports / finance vertical adapters resolve
 * the captured city to coordinates via [cityCoords] and substitute them
 * into URL templates (`{lat}` / `{lon}`), so endpoints like
 * `weather.gc.ca/en/location/index.html?coords={lat},{lon}` work for
 * every shipped city without a runtime GPS permission.
 */
class LocationCatalog(jsonText: String) {

    private val file: CatalogFile = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }.decodeFromString(CatalogFile.serializer(), jsonText)

    fun countries(): List<CountryEntry> = file.countries

    fun country(code: String): CountryEntry? =
        file.countries.firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun region(country: String, regionCode: String): RegionEntry? =
        country(country)?.regions?.firstOrNull { it.code.equals(regionCode, ignoreCase = true) }

    /**
     * Look up a city's coordinates from the bundled catalog. Returns null
     * when the catalog doesn't carry the (country, region, city) triple —
     * either an unknown country, a city the user typed that isn't in the
     * dropdown, or the asset hasn't been migrated to the new shape yet.
     *
     * City names are compared case-insensitively to tolerate "toronto"
     * vs "Toronto" from older user records.
     */
    fun cityCoords(country: String, regionCode: String, city: String): GpsCoordinates? {
        val cityEntry = region(country, regionCode)
            ?.cities
            ?.firstOrNull { it.name.equals(city, ignoreCase = true) }
            ?: return null
        return GpsCoordinates(cityEntry.lat, cityEntry.lon)
    }

    @Serializable
    data class CatalogFile(val countries: List<CountryEntry>)

    @Serializable
    data class CountryEntry(
        val code: String,
        val name: String,
        val regions: List<RegionEntry> = emptyList(),
    )

    @Serializable
    data class RegionEntry(
        val code: String,
        val name: String,
        val cities: List<CityEntry> = emptyList(),
    )

    @Serializable
    data class CityEntry(
        val name: String,
        val lat: Double,
        val lon: Double,
    )
}

/**
 * Geographic coordinates (decimal degrees, WGS84) used to substitute
 * `{lat}` / `{lon}` placeholders into vertical-adapter URL templates.
 * Resolved from the bundled [LocationCatalog] at search-dispatch time —
 * NOT from device GPS (no runtime permission, no battery cost, no
 * accuracy variability across locales).
 *
 * Precision matches the catalog's storage (4 decimal places ≈ 11 m at
 * the equator) — sufficient for city-scale forecast / news queries.
 */
data class GpsCoordinates(
    val latitude: Double,
    val longitude: Double,
)
