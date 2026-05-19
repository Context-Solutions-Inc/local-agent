package com.contextsolutions.mobileagent.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Android implementation of [SearchPreferencesRepository] backed by Jetpack
 * DataStore Preferences. Two keys: `location_json` (serialised
 * [UserLocation]?) and `vertical_prefs_json` (serialised
 * [VerticalPreferences]). JSON-in-a-key keeps the storage schema-free —
 * adding fields just means decoding with `ignoreUnknownKeys = true` and
 * letting older blobs continue to round-trip.
 *
 * Defaults are seeded lazily: first read returns
 * `DefaultSiteResolver.defaultsFor(location.country)` when nothing's been
 * written yet. Once the user touches a vertical (Sources screen "Save"),
 * the user's list wins via [DefaultSiteResolver.merge].
 */
class DataStoreSearchPreferencesRepository(
    context: Context,
    private val resolver: DefaultSiteResolver,
) : SearchPreferencesRepository {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.searchPreferencesDataStore

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override suspend fun snapshot(): VerticalPreferences = flow().first()

    override fun flow(): Flow<VerticalPreferences> = dataStore.data.map { prefs ->
        val location = prefs[LOCATION_KEY]?.let { decodeLocation(it) }
        val stored = prefs[VERTICAL_PREFS_KEY]?.let { decodeVertical(it) } ?: VerticalPreferences()
        val defaults = resolver.defaultsFor(location?.country ?: DEFAULT_COUNTRY)
        DefaultSiteResolver.merge(defaults = defaults, user = stored)
    }

    override suspend fun location(): UserLocation? =
        dataStore.data.first()[LOCATION_KEY]?.let { decodeLocation(it) }

    override suspend fun setLocation(location: UserLocation) {
        dataStore.edit { prefs ->
            prefs[LOCATION_KEY] = json.encodeToString(UserLocation.serializer(), location)
            // Re-seed defaults for the new country so the user immediately
            // sees country-appropriate sources after picking location, even
            // if they didn't visit the Sources step yet. Existing user
            // overrides for any vertical with a non-empty list are
            // preserved by merge() at read time.
            val existing = prefs[VERTICAL_PREFS_KEY]?.let { decodeVertical(it) }
            val defaults = resolver.defaultsFor(location.country)
            val seeded = if (existing == null) defaults
                else DefaultSiteResolver.merge(defaults = defaults, user = existing)
            prefs[VERTICAL_PREFS_KEY] = json.encodeToString(VerticalPreferences.serializer(), seeded)
        }
    }

    override suspend fun setSites(subtype: SearchSubtype, sites: List<SiteConfig>) {
        dataStore.edit { prefs ->
            val current = prefs[VERTICAL_PREFS_KEY]?.let { decodeVertical(it) } ?: VerticalPreferences()
            val updated = current.withSites(subtype, sites)
            prefs[VERTICAL_PREFS_KEY] = json.encodeToString(VerticalPreferences.serializer(), updated)
        }
    }

    override suspend fun isOnboarded(): Boolean =
        dataStore.data.first()[LOCATION_KEY] != null

    private fun decodeLocation(raw: String): UserLocation? = try {
        json.decodeFromString(UserLocation.serializer(), raw)
    } catch (_: Throwable) {
        null
    }

    private fun decodeVertical(raw: String): VerticalPreferences? = try {
        json.decodeFromString(VerticalPreferences.serializer(), raw)
    } catch (_: Throwable) {
        null
    }

    private companion object {
        private const val DATASTORE_NAME = "search_preferences"
        private const val DEFAULT_COUNTRY = "US"

        // Property delegate creates a singleton DataStore per process/file.
        private val Context.searchPreferencesDataStore by preferencesDataStore(name = DATASTORE_NAME)

        private val LOCATION_KEY = stringPreferencesKey("location_json")
        private val VERTICAL_PREFS_KEY = stringPreferencesKey("vertical_prefs_json")
    }
}
