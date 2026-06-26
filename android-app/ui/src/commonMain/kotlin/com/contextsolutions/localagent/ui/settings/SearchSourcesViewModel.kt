package com.contextsolutions.localagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.localagent.preferences.LocationCatalog
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.SiteConfig
import com.contextsolutions.localagent.preferences.SourceKind
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.search.SearchSubtype
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Backs the "Search sources" Settings screen. Reads the user's vertical
 * preferences via [SearchPreferencesRepository.flow] so live edits round-trip
 * immediately without manual refresh.
 */
class SearchSourcesViewModel(
    private val repository: SearchPreferencesRepository,
    locationCatalog: LocationCatalog,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Countries the user can pick as their search-defaults country (PR #22). */
    val countries: List<LocationCatalog.CountryEntry> = locationCatalog.countries()

    init {
        repository.flow()
            .onEach { prefs ->
                val location = repository.location()
                _state.value = _state.value.copy(prefs = prefs, location = location)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Change the default country: overwrites ALL five vertical source lists with
     * the chosen country's defaults (PR #22). Destructive by design — the picker
     * is a "reset to this country's defaults" action.
     */
    fun changeCountry(code: String) {
        viewModelScope.launch {
            repository.resetToCountryDefaults(code)
        }
    }

    fun removeSite(subtype: SearchSubtype, domain: String) {
        viewModelScope.launch {
            val current = repository.snapshot().sitesFor(subtype)
            val updated = current.filterNot { it.domain.equals(domain, ignoreCase = true) }
            repository.setSites(subtype, updated)
        }
    }

    fun addSite(
        subtype: SearchSubtype,
        domain: String,
        displayName: String,
        kind: SourceKind,
        endpointTemplate: String,
    ) {
        val cleaned = domain.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            val current = repository.snapshot().sitesFor(subtype)
            val newSite = SiteConfig(
                domain = cleaned,
                displayName = displayName.ifBlank { cleaned },
                kind = kind,
                endpointTemplate = endpointTemplate.ifBlank {
                    if (kind == SourceKind.BRAVE_SITE_FILTER) cleaned else "https://$cleaned"
                },
            )
            // Preserve order; refuse duplicates by domain.
            val withoutDupe = current.filterNot { it.domain.equals(cleaned, ignoreCase = true) }
            repository.setSites(subtype, withoutDupe + newSite)
        }
    }

    /**
     * Replace the source identified by [originalDomain] in-place (preserving
     * its position in the list). The domain itself may change; if the new
     * domain collides with a *different* existing entry, that older duplicate
     * is dropped so the just-edited entry wins.
     */
    fun editSite(
        subtype: SearchSubtype,
        originalDomain: String,
        domain: String,
        displayName: String,
        kind: SourceKind,
        endpointTemplate: String,
    ) {
        val cleaned = domain.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            val current = repository.snapshot().sitesFor(subtype)
            val index = current.indexOfFirst { it.domain.equals(originalDomain, ignoreCase = true) }
            if (index < 0) return@launch
            val newSite = SiteConfig(
                domain = cleaned,
                displayName = displayName.ifBlank { cleaned },
                kind = kind,
                endpointTemplate = endpointTemplate.ifBlank {
                    if (kind == SourceKind.BRAVE_SITE_FILTER) cleaned else "https://$cleaned"
                },
            )
            val updated = current.toMutableList().apply { this[index] = newSite }
            // Drop any OTHER entry the rename now duplicates (keep the edited one).
            val deduped = updated.filterIndexed { i, site ->
                i == index || !site.domain.equals(cleaned, ignoreCase = true)
            }
            repository.setSites(subtype, deduped)
        }
    }

    data class State(
        val prefs: VerticalPreferences = VerticalPreferences(),
        val location: UserLocation? = null,
    )
}
