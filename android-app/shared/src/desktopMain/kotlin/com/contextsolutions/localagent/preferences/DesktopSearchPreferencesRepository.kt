package com.contextsolutions.localagent.preferences

import com.contextsolutions.localagent.platform.DesktopJsonStore
import com.contextsolutions.localagent.search.SearchSubtype
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Desktop [SearchPreferencesRepository] backed by a [DesktopJsonStore] (Phase 6),
 * mirroring Android's `DataStoreSearchPreferencesRepository` — same two
 * JSON-in-a-key blobs (`location_json`, `vertical_prefs_json`) and the same
 * default-seeding semantics ([DefaultSiteResolver.defaultsFor] on read +
 * [DefaultSiteResolver.merge] so user overrides win). DataStore's reactive
 * `flow()` is replaced by a [MutableStateFlow] re-emitted after each mutation.
 */
class DesktopSearchPreferencesRepository(
    private val store: DesktopJsonStore,
    private val resolver: DefaultSiteResolver,
) : SearchPreferencesRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val merged = MutableStateFlow(computeMerged())

    override suspend fun snapshot(): VerticalPreferences = computeMerged()

    override fun flow(): Flow<VerticalPreferences> = merged.asStateFlow()

    override suspend fun location(): UserLocation? =
        store.getString(LOCATION_KEY)?.let(::decodeLocation)

    override suspend fun setLocation(location: UserLocation) {
        store.putString(LOCATION_KEY, json.encodeToString(UserLocation.serializer(), location))
        val existing = store.getString(VERTICAL_PREFS_KEY)?.let(::decodeVertical)
        val defaults = resolver.defaultsFor(location.country)
        val seeded = if (existing == null) defaults else DefaultSiteResolver.merge(defaults = defaults, user = existing)
        store.putString(VERTICAL_PREFS_KEY, json.encodeToString(VerticalPreferences.serializer(), seeded))
        merged.value = computeMerged()
    }

    override suspend fun setSites(subtype: SearchSubtype, sites: List<SiteConfig>) {
        val current = store.getString(VERTICAL_PREFS_KEY)?.let(::decodeVertical) ?: VerticalPreferences()
        store.putString(VERTICAL_PREFS_KEY, json.encodeToString(VerticalPreferences.serializer(), current.withSites(subtype, sites)))
        merged.value = computeMerged()
    }

    override suspend fun isOnboarded(): Boolean = store.getString(LOCATION_KEY) != null

    private fun computeMerged(): VerticalPreferences {
        val location = store.getString(LOCATION_KEY)?.let(::decodeLocation)
        val stored = store.getString(VERTICAL_PREFS_KEY)?.let(::decodeVertical) ?: VerticalPreferences()
        val defaults = resolver.defaultsFor(location?.country ?: DEFAULT_COUNTRY)
        return DefaultSiteResolver.merge(defaults = defaults, user = stored)
    }

    private fun decodeLocation(raw: String): UserLocation? =
        runCatching { json.decodeFromString(UserLocation.serializer(), raw) }.getOrNull()

    private fun decodeVertical(raw: String): VerticalPreferences? =
        runCatching { json.decodeFromString(VerticalPreferences.serializer(), raw) }.getOrNull()

    private companion object {
        const val DEFAULT_COUNTRY = "US"
        const val LOCATION_KEY = "location_json"
        const val VERTICAL_PREFS_KEY = "vertical_prefs_json"
    }
}
