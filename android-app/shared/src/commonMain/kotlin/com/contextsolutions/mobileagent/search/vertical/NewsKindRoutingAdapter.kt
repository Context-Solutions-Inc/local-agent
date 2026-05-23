package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.search.SearchSubtype
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * NEWS composite that routes each configured source to the adapter that can
 * actually parse its [SourceKind], so the NEWS vertical can exercise ALL five
 * kinds (JSON / RSS / DWML / HTML via [FeedAdapter]; BRAVE_SITE_FILTER via
 * [BraveSiteFilterAdapter]). Before PR #47 NEWS was wired to
 * [BraveSiteFilterAdapter] alone, which `.filter`s its site list to
 * BRAVE_SITE_FILTER and silently dropped the other four kinds (invariant #31's
 * "exactly one adapter per subtype" no longer holds for NEWS).
 *
 * Anti-pollution rule: each child runs ONLY if the user's NEWS site list has a
 * source it handles. This matters for the Brave side — [BraveSiteFilterAdapter]
 * with zero BRAVE_SITE_FILTER domains issues an *unfiltered* full-web Brave
 * search and returns Success, which would contaminate a feed-only NEWS test
 * with unrelated web results.
 *
 * Merge: if only one side ran, its outcome is returned untouched (the common
 * path — default NEWS ships Brave-only apnews.com + reuters.com, so this stays
 * a single Brave call with today's exact payload shape). If both succeed, the
 * citation chips are concatenated/deduped/capped at [maxMergedCitations] and
 * the two `payload.json` blocks are wrapped in one parseable envelope
 * `{"subtype":"news","query":…,"feeds":<feed.json>,"web":<brave.json>}`.
 * AgentLoop treats `payload.json` as opaque text (caps at 3600 chars) so the
 * envelope shape is for debuggability; both children are already byte-capped.
 */
class NewsKindRoutingAdapter(
    private val feedAdapter: VerticalSearchAdapter,
    private val braveAdapter: VerticalSearchAdapter,
    private val subtype: SearchSubtype = SearchSubtype.NEWS,
    private val maxMergedCitations: Int = DEFAULT_MAX_MERGED_CITATIONS,
    private val logger: (String) -> Unit = {},
) : VerticalSearchAdapter {

    private val json = Json { encodeDefaults = false; prettyPrint = false; ignoreUnknownKeys = true }

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome {
        val sites = prefs.sitesFor(subtype)
        val hasFeed = sites.any { it.kind != SourceKind.BRAVE_SITE_FILTER }
        val hasBrave = sites.any { it.kind == SourceKind.BRAVE_SITE_FILTER }
        logger("[vertical:$subtype] routing feed=$hasFeed brave=$hasBrave (${sites.size} sources)")

        if (!hasFeed && !hasBrave) {
            return SearchOutcome.Error(
                SearchOutcome.ErrorKind.NoKey,
                "No sources configured for ${subtype.name.lowercase()}",
            )
        }

        val feedOutcome = if (hasFeed) feedAdapter.fetch(query, prefs, location, gps) else null
        val braveOutcome = if (hasBrave) braveAdapter.fetch(query, prefs, location, gps) else null

        val feedOk = feedOutcome as? SearchOutcome.Success
        val braveOk = braveOutcome as? SearchOutcome.Success

        return when {
            feedOk != null && braveOk != null -> merge(query, feedOk, braveOk)
            feedOk != null -> feedOk
            braveOk != null -> braveOk
            // Neither succeeded — surface the feed error first (it carries
            // per-source detail); fall back to the brave error otherwise.
            else -> mergeErrors(feedOutcome, braveOutcome)
        }
    }

    private fun merge(
        query: String,
        feed: SearchOutcome.Success,
        brave: SearchOutcome.Success,
    ): SearchOutcome.Success {
        val sources = dedupeByUrl(feed.payload.sources + brave.payload.sources).take(maxMergedCitations)
        val envelope = buildJsonObject {
            put("subtype", subtype.name.lowercase())
            put("query", query)
            put("feeds", feed.payload.json.toJsonElementOrString())
            put("web", brave.payload.json.toJsonElementOrString())
        }
        return SearchOutcome.Success(
            payload = FormattedSearchPayload(
                json = json.encodeToString(JsonObject.serializer(), envelope),
                sources = sources,
            ),
            fromCache = feed.fromCache && brave.fromCache,
        )
    }

    private fun mergeErrors(
        feed: SearchOutcome?,
        brave: SearchOutcome?,
    ): SearchOutcome.Error {
        val feedErr = feed as? SearchOutcome.Error
        val braveErr = brave as? SearchOutcome.Error
        val primary = feedErr ?: braveErr
        val kind = when {
            feedErr?.kind == SearchOutcome.ErrorKind.Network ||
                braveErr?.kind == SearchOutcome.ErrorKind.Network -> SearchOutcome.ErrorKind.Network
            else -> primary?.kind ?: SearchOutcome.ErrorKind.Network
        }
        val message = listOfNotNull(feedErr?.message, braveErr?.message)
            .joinToString(separator = "; ")
            .ifBlank { "All ${subtype.name.lowercase()} sources failed" }
        return SearchOutcome.Error(kind, message)
    }

    private fun dedupeByUrl(sources: List<SearchSource>): List<SearchSource> {
        val seen = mutableSetOf<String>()
        return sources.filter { seen.add(it.url.trim()) }
    }

    /**
     * Re-parse a child adapter's `payload.json` so it embeds as structured JSON
     * in the envelope rather than a quoted string. Falls back to the raw string
     * if it isn't valid JSON (shouldn't happen — both children emit valid JSON).
     */
    private fun String.toJsonElementOrString(): JsonElement =
        runCatching { json.parseToJsonElement(this) }
            .getOrElse { kotlinx.serialization.json.JsonPrimitive(this) }

    private companion object {
        const val DEFAULT_MAX_MERGED_CITATIONS = 10
    }
}
