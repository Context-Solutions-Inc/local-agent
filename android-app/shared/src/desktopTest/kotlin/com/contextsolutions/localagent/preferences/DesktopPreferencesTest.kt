package com.contextsolutions.localagent.preferences

import com.contextsolutions.localagent.memory.DesktopMemoryPreferences
import com.contextsolutions.localagent.platform.DesktopJsonStore
import com.contextsolutions.localagent.search.SearchSubtype
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Round-trips the file-backed desktop preference repos (Phase 6) — persistence,
 * reopen, default-seeding from [DefaultSiteResolver], and the reactive flow.
 * Runs in CI (no model/network).
 */
class DesktopPreferencesTest {

    private val tmpDir: File = Files.createTempDirectory("prefs-test").toFile()

    // Minimal defaults JSON: CA seeds a weather source, US is empty.
    private val resolver = DefaultSiteResolver(
        """{"fallback":"US","countries":{
            "CA":{"weather":[{"domain":"weather.gc.ca","displayName":"Env Canada","kind":"RSS","endpointTemplate":"https://weather.gc.ca/rss/city/{city}.xml"}]},
            "US":{}
        }}""",
    )

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun searchRepo(file: String = "search_prefs.json") =
        DesktopSearchPreferencesRepository(DesktopJsonStore(File(tmpDir, file)), resolver)

    @Test
    fun memory_preferences_default_and_persist() {
        val store = DesktopJsonStore(File(tmpDir, "memory_prefs.json"))
        val prefs = DesktopMemoryPreferences(store)
        assertTrue(prefs.creationEnabled(), "defaults to enabled")
        prefs.setCreationEnabled(false)
        assertFalse(prefs.creationEnabled())
        // Reopen over the same file.
        assertFalse(DesktopMemoryPreferences(DesktopJsonStore(File(tmpDir, "memory_prefs.json"))).creationEnabled())
    }

    @Test
    fun search_prefs_seed_defaults_and_onboarded_flag() = runTest {
        val repo = searchRepo()
        assertNull(repo.location(), "no location before onboarding")
        assertFalse(repo.isOnboarded())

        repo.setLocation(UserLocation(country = "CA", regionCode = "", city = ""))
        assertTrue(repo.isOnboarded())
        assertEquals("CA", repo.location()?.country)

        // CA defaults seed a weather source via the resolver.
        val snapshot = repo.snapshot()
        assertEquals("weather.gc.ca", snapshot.weather.single().domain)
        // Flow emits the current merged value.
        assertEquals("weather.gc.ca", repo.flow().first().weather.single().domain)
    }

    @Test
    fun search_prefs_user_sites_persist_across_reopen() = runTest {
        searchRepo().setSites(
            SearchSubtype.NEWS,
            listOf(SiteConfig("apnews.com", "AP", SourceKind.BRAVE_SITE_FILTER, "")),
        )
        // Fresh repo over the same file sees the user override.
        assertEquals("apnews.com", searchRepo().snapshot().news.single().domain)
    }
}
