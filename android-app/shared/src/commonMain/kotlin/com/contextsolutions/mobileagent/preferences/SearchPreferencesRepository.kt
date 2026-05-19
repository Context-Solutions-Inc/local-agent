package com.contextsolutions.mobileagent.preferences

import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Per-user persistent state for vertical search routing (PR #23):
 *
 *  1. [UserLocation] — captured during onboarding (Country → State/Province →
 *     City). Used to seed sensible per-vertical defaults via
 *     [DefaultSiteResolver]. Editable from Settings.
 *  2. [VerticalPreferences] — five lists of [SiteConfig], one per
 *     [SearchSubtype]. Onboarding-Sources screen pre-populates from the
 *     resolver, then writes any user adjustments back through [setSites].
 *
 * Persistence: Android impl uses Jetpack DataStore Preferences. Two keys
 * holding JSON-serialised blobs (`location_json`, `vertical_prefs_json`).
 * The JSON-in-a-key strategy sidesteps SQLDelight's `.sqm` snapshot dance
 * (invariant #20) — schema evolution is just a version field inside the
 * blob.
 */
interface SearchPreferencesRepository {

    /** Snapshot-read the current preferences, applying defaults if unset. */
    suspend fun snapshot(): VerticalPreferences

    /** Hot flow of preference updates; emits the current value immediately. */
    fun flow(): Flow<VerticalPreferences>

    /** Snapshot-read the captured location, or null if onboarding hasn't run. */
    suspend fun location(): UserLocation?

    /**
     * Persist [location] AND seed [VerticalPreferences] from
     * [DefaultSiteResolver] for the new country code (preserving any existing
     * user overrides for verticals the user has already touched). Onboarding
     * calls this once; Settings can call again if the user changes location.
     */
    suspend fun setLocation(location: UserLocation)

    /**
     * Replace the site list for a single [subtype]. Settings/Onboarding-Sources
     * write through this. The other four lists are left untouched.
     */
    suspend fun setSites(subtype: SearchSubtype, sites: List<SiteConfig>)

    /** True once onboarding has captured a location (sites may still be defaults). */
    suspend fun isOnboarded(): Boolean
}

/**
 * Where the user lives, used to pick country-appropriate default sources
 * (weather.gc.ca for CA, api.weather.gov for US, etc.). The fields are
 * captured at onboarding via the three-stage Country → Region → City
 * dropdowns and persisted verbatim.
 *
 * @property country ISO-3166-1 alpha-2 country code, uppercase (e.g. "CA").
 * @property regionCode ISO-3166-2 subdivision suffix (e.g. "ON" for Ontario;
 *   the full code is composed as `"$country-$regionCode"` only when needed).
 *   Empty string when the country has no subdivisions in the bundled
 *   `locations.json`.
 * @property city Free-form city name as it appears in `locations.json`
 *   ("Toronto", "San Francisco"). Used for URL substitution in vertical
 *   adapters that take a city parameter.
 */
@Serializable
data class UserLocation(
    val country: String,
    val regionCode: String,
    val city: String,
)

/**
 * One configured source (site/API/feed) inside a vertical's site list.
 *
 * @property domain Bare domain shown in the UI ("weather.gc.ca",
 *   "cbc.ca"). Used as the user-visible label and for `site:`-filter
 *   queries when [kind] == [SourceKind.HTML] inside a Brave-adapter.
 * @property displayName Human-friendly label for chips/cards. Defaults to
 *   the domain when unspecified by the defaults JSON.
 * @property kind Tells the adapter how to fetch + parse this source.
 * @property endpointTemplate URL template with `{lat}`, `{lon}`, `{city}`,
 *   `{region}`, `{country}`, `{query}` placeholders. Adapters substitute
 *   before issuing the HTTP request. For RSS feeds, typically a static URL
 *   without placeholders; for JSON APIs, often city/coord-parametrised.
 */
@Serializable
data class SiteConfig(
    val domain: String,
    val displayName: String,
    val kind: SourceKind,
    val endpointTemplate: String,
)

/**
 * How a vertical adapter should fetch and decode a source.
 *
 * - [JSON] — GET the endpoint, deserialise via the per-vertical typed model.
 * - [RSS] — GET, parse as RSS 2.0 / Atom, format top-N entries.
 * - [HTML] — GET, run through [com.contextsolutions.mobileagent.search.vertical.HtmlReadabilityExtractor].
 * - [BRAVE_SITE_FILTER] — News-only, reuses the Brave web search with a
 *   `site:` filter for this domain.
 */
@Serializable
enum class SourceKind {
    JSON,
    RSS,
    HTML,
    BRAVE_SITE_FILTER,
}

/**
 * One configured list of sources per vertical, in user-preferred order.
 * Adapters iterate this list and merge / dedupe at the structured-extract
 * layer.
 */
@Serializable
data class VerticalPreferences(
    val general: List<SiteConfig> = emptyList(),
    val news: List<SiteConfig> = emptyList(),
    val weather: List<SiteConfig> = emptyList(),
    val sports: List<SiteConfig> = emptyList(),
    val finance: List<SiteConfig> = emptyList(),
) {
    fun sitesFor(subtype: SearchSubtype): List<SiteConfig> = when (subtype) {
        SearchSubtype.GENERAL -> general
        SearchSubtype.NEWS -> news
        SearchSubtype.WEATHER -> weather
        SearchSubtype.SPORTS -> sports
        SearchSubtype.FINANCE -> finance
    }

    fun withSites(subtype: SearchSubtype, sites: List<SiteConfig>): VerticalPreferences =
        when (subtype) {
            SearchSubtype.GENERAL -> copy(general = sites)
            SearchSubtype.NEWS -> copy(news = sites)
            SearchSubtype.WEATHER -> copy(weather = sites)
            SearchSubtype.SPORTS -> copy(sports = sites)
            SearchSubtype.FINANCE -> copy(finance = sites)
        }
}
