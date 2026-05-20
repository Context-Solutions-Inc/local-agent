package com.contextsolutions.mobileagent.search.vertical

import com.contextsolutions.mobileagent.preferences.DefaultSiteResolver
import com.contextsolutions.mobileagent.preferences.GpsCoordinates
import com.contextsolutions.mobileagent.preferences.SiteConfig
import com.contextsolutions.mobileagent.preferences.SourceKind
import com.contextsolutions.mobileagent.preferences.UserLocation
import com.contextsolutions.mobileagent.preferences.VerticalPreferences
import com.contextsolutions.mobileagent.search.FormattedSearchPayload
import com.contextsolutions.mobileagent.search.SearchOutcome
import com.contextsolutions.mobileagent.search.SearchSource
import com.contextsolutions.mobileagent.search.SearchSubtype
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generic adapter for WEATHER / SPORTS / FINANCE verticals. Iterates the
 * site list for [subtype] in user-preferred order and dispatches per
 * [SiteConfig.kind]:
 *
 *  - [SourceKind.RSS] → fetch + parse via [RssParser]
 *  - [SourceKind.HTML] → fetch + extract via [HtmlReadabilityExtractor]
 *  - [SourceKind.JSON] → fetch + pass raw body through (downstream typed
 *    parsing per vertical is a follow-up; for now the raw JSON snippet
 *    feeds Gemma, which can synthesise the rest)
 *  - [SourceKind.BRAVE_SITE_FILTER] → skipped (only meaningful for NEWS;
 *    [BraveSiteFilterAdapter] handles that case)
 *
 * Per source: a [PER_SOURCE_CHAR_CAP]-char cap on the extracted text keeps
 * the per-source token budget at ~600. The combined payload is wrapped in
 * the existing [FormattedSearchPayload] so the agent loop's citation
 * injection works unchanged.
 */
class FeedAdapter(
    private val subtype: SearchSubtype,
    private val httpClient: HttpClient,
    private val rssParser: RssParser = RssParser(),
    private val readability: HtmlReadabilityExtractor = HtmlReadabilityExtractor(),
    private val maxSources: Int = DEFAULT_MAX_SOURCES,
    private val logger: (String) -> Unit = {},
) : VerticalSearchAdapter {

    private val json = Json { encodeDefaults = false; prettyPrint = false }

    override suspend fun fetch(
        query: String,
        prefs: VerticalPreferences,
        location: UserLocation?,
        gps: GpsCoordinates?,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val sites = prefs.sitesFor(subtype).take(maxSources)
        if (sites.isEmpty()) {
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.NoKey,
                "No sources configured for ${subtype.name.lowercase()}",
            )
        }

        logger(
            "[vertical:$subtype] sites=${sites.joinToString { "${it.domain}(${it.kind.name})" }} " +
                "gps=${gps?.let { "${it.latitude},${it.longitude}" } ?: "null"} " +
                "location=${location?.let { "${it.country}/${it.regionCode}/${it.city}" } ?: "null"}",
        )
        val perSource = mutableListOf<SourceBundle>()
        var anyOk = false
        var lastError: String? = null
        for (site in sites) {
            val url = DefaultSiteResolver.applyPlaceholders(
                template = site.endpointTemplate,
                location = location,
                query = query,
                gps = gps,
            )
            if (url == null) {
                // Template needs {lat}/{lon} but we have no city coordinates
                // — skip with a log line so the operator can see why the
                // top-priority weather source got bypassed.
                lastError = "${site.domain}: skipped — template needs {lat}/{lon} but no city coords available"
                logger("[vertical:$subtype] $lastError")
                continue
            }
            logger("[vertical:$subtype] GET ${site.domain} kind=${site.kind.name} url=$url")
            try {
                val body = httpClient.get(url).bodyAsText()
                logger("[vertical:$subtype] ${site.domain} bodyLen=${body.length}")
                val (snippet, payloadBlock) = when (site.kind) {
                    SourceKind.RSS -> {
                        val entries = rssParser.parse(body, max = RSS_ENTRIES_PER_SOURCE)
                        if (entries.isEmpty()) {
                            logger(
                                "[vertical:$subtype] ${site.domain} RSS parse returned 0 entries — " +
                                    "body starts with: \"${body.take(200).replace("\n", " ")}\"",
                            )
                            null to null
                        } else {
                            // Per-entry snippet: title on its own line, then the
                            // cleaned description (HTML-stripped + entity-decoded
                            // by RssParser). Weather feeds especially need the
                            // description — the title alone says "Tuesday: Chance
                            // of showers. High 29. POP 30%" but the description
                            // carries wind direction, humidex, UV, thunder risk.
                            val snippet = entries.joinToString(separator = "\n") { e ->
                                if (e.description.isBlank()) e.title
                                else "${e.title} — ${e.description}"
                            }.take(PER_SOURCE_CHAR_CAP)
                            val payload = buildJsonArray {
                                for (e in entries) add(buildJsonObject {
                                    put("title", e.title)
                                    put("link", e.link)
                                    put("description", e.description)
                                    if (e.pubDate != null) put("published", e.pubDate)
                                })
                            }
                            snippet to payload
                        }
                    }
                    SourceKind.HTML -> {
                        val text = readability.extract(body)
                        if (text.isBlank()) {
                            logger(
                                "[vertical:$subtype] ${site.domain} HTML extract returned blank — " +
                                    "body starts with: \"${body.take(200).replace("\n", " ")}\"",
                            )
                            null to null
                        } else {
                            logger(
                                "[vertical:$subtype] ${site.domain} HTML extracted ${text.length} chars — " +
                                    "starts: \"${text.take(120).replace("\n", " ")}\"",
                            )
                            text.take(PER_SOURCE_CHAR_CAP) to JsonObject(mapOf())
                        }
                    }
                    SourceKind.JSON -> {
                        // No per-vertical typed model yet; pass raw body
                        // through truncated. Downstream Gemma synthesis can
                        // still extract value because the byte cap keeps the
                        // JSON small enough to fit.
                        body.take(PER_SOURCE_CHAR_CAP) to JsonObject(mapOf())
                    }
                    SourceKind.BRAVE_SITE_FILTER -> {
                        // Not this adapter's job — silently skip so the user
                        // can mix Brave entries into a vertical's site list
                        // without the adapter erroring.
                        null to null
                    }
                }
                if (snippet == null) {
                    lastError = "no content extracted from ${site.domain}"
                    continue
                }
                perSource.add(SourceBundle(site = site, url = url, snippet = snippet, json = payloadBlock))
                anyOk = true
            } catch (t: Throwable) {
                lastError = "${site.domain}: ${t.message ?: t::class.simpleName}"
                logger("[vertical:$subtype] fetch failed for ${site.domain}: $lastError")
            }
        }

        if (!anyOk) {
            return@withContext SearchOutcome.Error(
                SearchOutcome.ErrorKind.Network,
                lastError ?: "All ${subtype.name.lowercase()} sources failed",
            )
        }

        val sources = perSource.map { (site, url, snippet, _) ->
            SearchSource(
                title = site.displayName,
                url = toHumanReadableUrl(url),
                snippet = snippet,
            )
        }
        val structured = buildJsonObject {
            put("subtype", subtype.name.lowercase())
            put("query", query)
            put("sources", buildJsonArray {
                for (b in perSource) add(buildJsonObject {
                    put("domain", b.site.domain)
                    put("display_name", b.site.displayName)
                    put("url", b.url)
                    put("snippet", b.snippet)
                    if (b.json != null) put("payload", b.json)
                })
            })
        }
        val payload = FormattedSearchPayload(
            json = json.encodeToString(JsonObject.serializer(), structured),
            sources = sources,
        )
        SearchOutcome.Success(payload, fromCache = false)
    }

    private data class SourceBundle(
        val site: SiteConfig,
        val url: String,
        val snippet: String,
        val json: kotlinx.serialization.json.JsonElement?,
    )

    private companion object {
        const val DEFAULT_MAX_SOURCES = 3
        // Per-source budget ~600 tokens of cleaned text. Weather feeds in
        // particular have alert + current + many forecast periods; the
        // outer SEARCH_CONTEXT_MAX_CHARS=3600 cap in AgentLoop keeps the
        // total bounded when multiple sources fire.
        const val PER_SOURCE_CHAR_CAP = 2400
        // Weather RSS from EC ships 14 entries (alert + current + 12
        // forecast periods). 8 lets us cover today + next 3 days at the
        // half-day granularity EC publishes.
        const val RSS_ENTRIES_PER_SOURCE = 8
    }
}

/**
 * Rewrite a fetched URL into the URL the user should land on when tapping
 * a citation chip. Some adapters fetch machine-readable XML/JSON whose
 * fetch URL is useless to a human (raw XML / API response); for those we
 * substitute the matching consumer-facing page on the same domain.
 *
 * Currently rewrites EC's per-coords weather RSS feed
 * (`weather.gc.ca/rss/weather/{lat}_{lon}_{e|f}.xml`) → the HTML
 * forecast page (`weather.gc.ca/en/location/index.html?coords={lat},{lon}`),
 * and the bundled default sports RSS feeds → each brand's consumer-facing
 * landing page (a feed URL like `sportsnet.ca/feed/` is raw XML, useless to
 * tap). Add cases here as new structured sources land.
 *
 * Falls through to the fetch URL for any source without a specific rule —
 * so a user-added custom RSS feed keeps its feed URL until a rule exists.
 */
fun toHumanReadableUrl(fetchedUrl: String): String {
    EC_RSS_COORDS_URL.matchEntire(fetchedUrl)?.let { m ->
        val lat = m.groupValues[1]
        val lon = m.groupValues[2]
        return "https://weather.gc.ca/en/location/index.html?coords=$lat,$lon"
    }
    SPORTS_FEED_LANDING[fetchedUrl]?.let { return it }
    return fetchedUrl
}

val EC_RSS_COORDS_URL: Regex = Regex(
    """https?://weather\.gc\.ca/rss/weather/(-?[0-9.]+)_(-?[0-9.]+)_[ef]\.xml""",
)

/**
 * Exact fetch-URL → consumer-landing-page map for the default sports RSS
 * feeds shipped in `search_defaults.json`. Keyed on the full feed URL (not a
 * domain prefix) so the `abc.net.au` NEWS source — which shares the domain
 * with ABC Sport — is never rewritten. BBC and ABC map to the brand's sport
 * section because the feed host (`feeds.bbci.co.uk`) isn't consumer-facing.
 */
val SPORTS_FEED_LANDING: Map<String, String> = mapOf(
    "https://www.tsn.ca/rss/news" to "https://www.tsn.ca/",
    "https://www.sportsnet.ca/feed/" to "https://www.sportsnet.ca/",
    "https://www.espn.com/espn/rss/news" to "https://www.espn.com/",
    "https://www.cbssports.com/rss/headlines/" to "https://www.cbssports.com/",
    "https://feeds.bbci.co.uk/sport/rss.xml" to "https://www.bbc.com/sport",
    "https://www.skysports.com/rss/12040" to "https://www.skysports.com/",
    "https://www.abc.net.au/news/feed/45924/rss.xml" to "https://www.abc.net.au/news/sport",
)
