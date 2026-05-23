package com.contextsolutions.mobileagent.app.ui.settings

import com.contextsolutions.mobileagent.preferences.SearchPreferencesRepository
import com.contextsolutions.mobileagent.preferences.SiteConfig
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Locks down the Search-sources edit flow added in PR #47. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSourcesViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `editSite replaces the entry in place preserving position`() = runTest {
        val repo = FakeRepo(
            news = listOf(rss("a.com"), rss("b.com"), rss("c.com")),
        )
        val vm = SearchSourcesViewModel(repo)
        advanceUntilIdle()

        vm.editSite(
            subtype = SearchSubtype.NEWS,
            originalDomain = "b.com",
            domain = "b2.com",
            displayName = "B Two",
            kind = SourceKind.HTML,
            endpointTemplate = "https://b2.com/news",
        )
        advanceUntilIdle()

        val news = repo.snapshot().sitesFor(SearchSubtype.NEWS)
        assertEquals(listOf("a.com", "b2.com", "c.com"), news.map { it.domain })
        val edited = news[1]
        assertEquals("B Two", edited.displayName)
        assertEquals(SourceKind.HTML, edited.kind)
        assertEquals("https://b2.com/news", edited.endpointTemplate)
    }

    @Test
    fun `editSite defaults a blank endpoint by kind`() = runTest {
        val repo = FakeRepo(news = listOf(rss("a.com")))
        val vm = SearchSourcesViewModel(repo)
        advanceUntilIdle()

        vm.editSite(SearchSubtype.NEWS, "a.com", "a.com", "", SourceKind.BRAVE_SITE_FILTER, "")
        advanceUntilIdle()

        val site = repo.snapshot().sitesFor(SearchSubtype.NEWS).single()
        assertEquals(SourceKind.BRAVE_SITE_FILTER, site.kind)
        // BRAVE_SITE_FILTER blank endpoint -> bare domain.
        assertEquals("a.com", site.endpointTemplate)
        // Blank display name -> domain.
        assertEquals("a.com", site.displayName)
    }

    @Test
    fun `editSite rename onto another entry drops the older duplicate`() = runTest {
        val repo = FakeRepo(news = listOf(rss("a.com"), rss("b.com")))
        val vm = SearchSourcesViewModel(repo)
        advanceUntilIdle()

        // Rename a.com -> b.com; the pre-existing b.com is dropped, edited wins.
        vm.editSite(SearchSubtype.NEWS, "a.com", "b.com", "Merged", SourceKind.RSS, "https://b.com/rss")
        advanceUntilIdle()

        val news = repo.snapshot().sitesFor(SearchSubtype.NEWS)
        assertEquals(listOf("b.com"), news.map { it.domain })
        assertEquals("Merged", news.single().displayName)
    }

    @Test
    fun `editSite on a missing domain is a no-op`() = runTest {
        val repo = FakeRepo(news = listOf(rss("a.com")))
        val vm = SearchSourcesViewModel(repo)
        advanceUntilIdle()

        vm.editSite(SearchSubtype.NEWS, "ghost.com", "x.com", "X", SourceKind.RSS, "")
        advanceUntilIdle()

        assertEquals(listOf("a.com"), repo.snapshot().sitesFor(SearchSubtype.NEWS).map { it.domain })
    }

    private fun rss(domain: String) =
        SiteConfig(domain, domain, SourceKind.RSS, "https://$domain/rss.xml")

    private class FakeRepo(news: List<SiteConfig> = emptyList()) : SearchPreferencesRepository {
        private val prefs = MutableStateFlow(VerticalPreferences(news = news))

        override suspend fun snapshot(): VerticalPreferences = prefs.value
        override fun flow(): Flow<VerticalPreferences> = prefs
        override suspend fun location(): UserLocation? = null
        override suspend fun setLocation(location: UserLocation) = Unit
        override suspend fun isOnboarded(): Boolean = true

        override suspend fun setSites(subtype: SearchSubtype, sites: List<SiteConfig>) {
            prefs.value = when (subtype) {
                SearchSubtype.GENERAL -> prefs.value.copy(general = sites)
                SearchSubtype.NEWS -> prefs.value.copy(news = sites)
                SearchSubtype.WEATHER -> prefs.value.copy(weather = sites)
                SearchSubtype.SPORTS -> prefs.value.copy(sports = sites)
                SearchSubtype.FINANCE -> prefs.value.copy(finance = sites)
            }
        }
    }
}
