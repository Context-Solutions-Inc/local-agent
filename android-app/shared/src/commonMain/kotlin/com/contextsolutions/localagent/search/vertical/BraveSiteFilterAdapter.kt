package com.contextsolutions.localagent.search.vertical

import com.contextsolutions.localagent.preferences.GpsCoordinates
import com.contextsolutions.localagent.preferences.SourceKind
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchPostProcessor
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.SearchSubtype

/**
 * Reuses [SearchService] (Brave) with a `site:` filter built from the user's
 * preferred domains for a [subtype]. Drives NEWS, SPORTS, and FINANCE: Brave
 * snippet quality is good for all three, and a web search restricted to e.g.
 * `espn.com` / `bloomberg.com` answers historical/specific queries ("who won
 * the masters last year", "nvidia stock price") that RSS headline feeds can't.
 * For finance this also subsumes the old per-instrument ticker resolver — one
 * query handles both market news and single-instrument quotes (PR #35). Only
 * weather still uses a direct fetch ([FeedAdapter]) for its structured data.
 *
 * Construction: rewrites `query` using up to [maxDomains] domains from
 * `prefs.sitesFor(subtype)` filtered to [SourceKind.BRAVE_SITE_FILTER]. A
 * single domain yields the bare `"$query site:domain"`; multiple domains are
 * parenthesised as `"$query (site:d1 OR site:d2 …)"` so the OR group binds.
 * Any non-Brave entries in the list are ignored (the user is free to mix kinds
 * in a vertical's site list).
 *
 * [maxCitations] (when non-null) caps the returned citation *chips* — used by
 * single-source verticals (FINANCE / SPORTS) so a single site doesn't yield
 * several redundant chips. The model's context (`payload.json`) keeps Brave's
 * full top-N, so this only affects the UI chips. NEWS leaves it null.
 */
class BraveSiteFilterAdapter(
    private val searchService: SearchService,
    private val maxDomains: Int = DEFAULT_MAX_DOMAINS,
    private val subtype: SearchSubtype = SearchSubtype.NEWS,
    private val maxCitations: Int? = null,
    private val logger: (String) -> Unit = {},
) : VerticalSearchAdapter {

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome {
        val domains = prefs.sitesFor(subtype)
            .filter { it.kind == SourceKind.BRAVE_SITE_FILTER }
            .map { it.endpointTemplate.ifBlank { it.domain } }
            .distinct()
            .take(maxDomains)
        val effectiveQuery = when (domains.size) {
            0 -> query
            // Single source (FINANCE / SPORTS, single-site NEWS): bare
            // `query site:domain` — no parens needed without an OR group.
            1 -> "$query site:${domains.first()}"
            // Multiple sources: parenthesise the OR group so the site terms
            // bind together rather than the last one floating onto the query.
            else -> "$query (${domains.joinToString(separator = " OR ") { "site:$it" }})"
        }
        logger("[vertical:$subtype] brave query=\"$effectiveQuery\"")
        val outcome = searchService.search(effectiveQuery)
        // Single-source verticals (FINANCE / SPORTS) cap the citation chips so a
        // single site doesn't render several redundant chips — the model still
        // receives Brave's full top-N for that domain as context.
        return if (maxCitations != null && outcome is SearchOutcome.Success) {
            outcome.copy(payload = SearchPostProcessor.limitCitations(outcome.payload, maxCitations))
        } else {
            outcome
        }
    }

    private companion object {
        const val DEFAULT_MAX_DOMAINS = 3
    }
}
