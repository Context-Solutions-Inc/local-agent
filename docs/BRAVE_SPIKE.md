# Brave Search API Spike — `news.results` + `/web/rich`

**Status:** RATIFIED — probed 2026-05-14 against Brave dev key. Decision recorded in §7: wire `news.results[]` in PR #12; defer `/web/rich` callback flow.
**Owner:** TBD
**Companion:** `PRD.md` §3.3, `SYSTEM_PROMPT.md` §6, `PHASE1_PLAN.md` §5 (M2 search integration)
**Probe artifacts:** `/tmp/brave-probe/0[1-9]*.json`, `/tmp/brave-probe/1[0-9]*.json`, `/tmp/brave-probe/news_*.json` (engineer machine — not committed)

---

## 1. Purpose

PR #8 shipped the `web_search` tool wired to Brave's `/web/search` endpoint, parsing only `web.results[]` into top-3 `{title, url, snippet}` source records (`SearchPostProcessor.kt:13-50`). The spike asked two questions that have come up repeatedly since:

1. **Does Brave expose structured cards** for the queries the classifier already labels as `markets_current`, `sports_recent`, `sports_upcoming`, `weather` (`CLASSIFIER_DATASETS.md:90-100`)? If so, what does the payload look like and what does it cost?
2. **Are we leaving useful data on the floor** by ignoring the other top-level blocks in the standard `/web/search` response — specifically `news.results[]`?

This memo records what we found, ratifies a near-term decision, and parks the rest as a future-work backlog so a fresh-context engineer can pick it up without re-probing.

---

## 2. Methodology

| | |
|---|---|
| Endpoint | `https://api.search.brave.com/res/v1/web/search` |
| Callback endpoint | `https://api.search.brave.com/res/v1/web/rich` |
| Auth | `X-Subscription-Token: $BRAVE_DEV_KEY` (from `android-app/secrets.properties`) |
| Rate-limit guard | 1.2 s sleep between calls (free-tier dev key is 1 qps) |
| Rich-callback flag | `enable_rich_callback=1` query param (required — absent flag returns no `rich` field at all) |
| Probe date | 2026-05-14 |
| Probe surface | curl + jq/python from `/tmp/brave-probe/`; no Android-side code touched |

Seasonal context (load-bearing — re-read before re-running this spike): NBA in playoffs, NHL in playoffs, MLB peak regular season, NFL deep offseason (May–Aug), F1 mid-season (last race Miami 2026-05-03, next race Canada 2026-05-24).

---

## 3. What `/web/search` already returns

Top-level keys on every probed response: `type, query, faq, mixed, news, videos, web` (plus `rich` when `enable_rich_callback=1` is set). Today's code only parses `web.results[]` (`BraveResponse.kt:14-33`).

The `news.results[]` block is **the most under-used surface**. It's populated on news-shaped queries with no flag required and no second round-trip. Schema per entry:

```json
{
  "title":        "At a glance: Starmer fights to stay on as prime minister",
  "url":          "https://www.bbc.com/...",
  "description":  "...",
  "age":          "6 hours ago",            // human-readable freshness
  "page_age":     "2026-05-14T...",         // ISO timestamp
  "fetched_content_timestamp": 1778768737,
  "breaking":     true,                     // explicit breaking flag
  "is_live":      false,                    // live-coverage flag
  "meta_url":     { "hostname": "www.bbc.com", "scheme": "https", "favicon": "..." },
  "family_friendly": true,
  "is_source_local": false,
  "is_source_both":  false
}
```

The `breaking` flag tracks reality well — only the `breaking news today` probe lit it on 5/5 top results; the other four news queries returned `breaking: false` across the board. `age` is news-cycle accurate ("5 hours ago" / "17 hours ago"), unlike `web.results[].age` which reflects static page-publish dates.

### 3.1 News probe coverage

| Query | `news.results` | `breaking` lit | Top sources |
|---|---|---|---|
| `breaking news today` | 15 | ✅ 5/5 | BBC, Guardian, NBC, Al Jazeera |
| `Ukraine war update` | 7 | 0/5 (correct — ongoing, not new) | ISW, Russia Matters, Al Jazeera |
| `earthquake Japan` | 1 | 0/1 | Kyodo News |
| `Federal Reserve interest rate` | 9 | 0/5 | CNBC, CBS, Quartz, NPR |
| `election results 2026` | 7 | 0/5 (primary recap, not breaking) | NYT, NBC, NPR |

`mixed.top[]` was empty across every news probe — so Brave is *not* promoting a news card into the answer-card slot. We have to surface it ourselves.

---

## 4. `/web/rich` callback flow (deferred — documented for future)

To unlock structured stock/weather/sports cards:

1. Add `enable_rich_callback=1` to the `/web/search` call.
2. If response has `rich.hint.{vertical, callback_key}`, make a **second** request to `/web/rich?callback_key=...`.
3. Parse the vertical-specific payload.

Supported verticals (per Brave docs + confirmed on dev key): `stocks` (FMP), `currency` (Fixer), `cryptocurrency` (CoinGecko), `weather` (OpenWeatherMap), `sports` (API-Sports — basketball/baseball/ice_hockey/football(soccer)/cricket), `formula1` (API-Sports), plus utility verticals (calculator, definitions, unit conversion, timestamp, package tracker).

### 4.1 Payload shapes confirmed

**`stocks`** (NVDA — 15 KB):
```json
results[0].stock = {
  asset_info: { symbol, name, exchange, mic_code, currency },
  quote: { latest_price, open, close, high, low, change, change_percent,
           market_cap, pe_ratio, week_52_high, week_52_low, volume, latest_update },
  timeseries: { time_range, timeseries: [{date, minute, close, high, low, volume}, ...] }
}
```

**`weather`** (Toronto, Tokyo — ~16 KB):
```json
results[0].weather = {
  location: { id, name, country, coords:{lat,lon}, tzoffset, implicit_location,
              sunrise, sunset },
  current_weather: { temp, feels_like, humidity, pressure, dew_point, uvi, clouds,
                     visibility, wind:{speed,deg}, weather:{main,description,icon} },
  daily: [ { date_i18n, temperature:{day,min,max,night,evening,morning},
             feels_like, wind, pressure, humidity, ... }, ... ]
}
```

`implicit_location: false` means Brave parsed the city from the query rather than defaulting to IP. Works on Toronto and Tokyo without canonicalization.

**Sports — team sports** (`subtype: "sports"`, with sport-block key in `{baseball, basketball, ice_hockey, football}`):
```json
results[0].<sport>.content = {
  type: "league_games" | "team_games",
  league: { id, name, season, ... },
  team?:  { id, name, logo },             // team_games only
  competitions?: [...],                   // soccer only — multi-comp seasons
  game_times?: [unix, ...],               // league_games only
  games: [
    { id,
      teams: { home: {id,name,logo}, away: {...} },
      score: { home, away },              // omitted on upcoming
      start_time, status: { code: "FT"|"NS"|"1H"|"2H"|"HT"|"AOT"|... },
      score_table?: { q1..q4 | p1..p3 },  // basketball quarters / hockey periods
      status.elapsed?, status.extra?      // soccer live minute display
    }, ...
  ]
}
```

**Formula 1** (`subtype: "formula1"`, ~18-62 KB — schema differs from team sports):
```json
results[0].formula1 = {
  default_view: "schedule",
  calendar: { races: [{id,name,date}], next_race: {...}, previous_race: {...} },
  previous_race: { event, standings:[{position,driver,team,time,laps,grid}],
                   qualifying, sprint_shootout, sprint_event, sprint_standings,
                   weekend_events },
  drivers:      [{position, driver, team, points, wins}, ...],  // 22 entries
  constructors: [{position, team, points, season}, ...]         // 11 entries
}
```

### 4.2 Hint coverage by vertical

Tested 2026-05-14. ✅ = `rich.hint` populated; ❌ = `rich: null`; ⚠️ = hint populated but `/web/rich` returned `results: []` (28-byte empty).

| Vertical | Probes that fired | Probes that returned empty | Probes that returned no hint |
|---|---|---|---|
| `stocks` | `NVDA stock price` | — | — |
| `weather` | `weather in Toronto`, `weather in Tokyo` | — | — |
| `sports` (NHL) | `NHL scores today`, `NHL scoreboard`, `Maple Leafs score`, `Rangers score`, `Edmonton Oilers result` | `Bruins vs Rangers` | — |
| `sports` (MLB) | `MLB scores today`, `MLB scoreboard`, `Yankees score`, `Dodgers score` | — | `Red Sox vs Yankees`, `World Series score` |
| `sports` (NBA) | `NBA scores today`, `NBA scoreboard` | — | `Lakers score`, `Knicks vs Lakers` |
| `sports` (soccer) | `Liverpool score`, `Real Madrid result`, `premier league scores`, `premier league results today`, `Champions League scores`, `El Clasico score` | `Arsenal vs Chelsea` | — |
| `sports` (NFL) | — | — | `NFL scores today`, `NFL scoreboard`, `Eagles score`, `Cowboys score`, `Eagles vs Cowboys`, `Super Bowl score` (all 6) |
| `formula1` | `F1 schedule`, `F1 race today`, `Monaco Grand Prix` | `F1 standings`, `F1 results` | `Lewis Hamilton` (driver name) |

Per-sport NLU competence gradient (most → least permissive on query phrasing):

```
soccer > NHL > MLB > NBA >> NFL (dark in offseason)
```

NBA-team-only queries (`Lakers score`) failing to fire is the largest unfixable-by-us gap. NFL is likely seasonal — re-probe Aug 2026.

---

## 5. Cross-cutting gotchas

1. **Hint fires ≠ payload populated.** Confirmed in soccer (`Arsenal vs Chelsea`), NHL (`Bruins vs Rangers`), F1 (`F1 standings`, `F1 results`). Every `/web/rich` consumer must check `results.isNotEmpty()` and fall back to web snippets when empty. Empty response is a literal 28-byte `{"type":"rich","results":[]}`.
2. **`enable_rich_callback=1` is mandatory.** Without it the `rich` field doesn't appear at all (confirmed: NVDA without flag had no `rich` key).
3. **Per-vertical schemas are NOT uniform.** Stock/weather use one shape, team sports share a `content.{type, games[]}` skeleton, F1 has no `content` wrapper and uses a season-wide dump. Any rich consumer needs vertical-specific parsing — there is no single `RichCard` model.
4. **Subtype + sport block dispatch.** For sports the routing requires three levels: `vertical == "sports"` → `results[0].<sport_block_name>` → `content.type`. F1's `vertical == "formula1"` collapses this to one level (`subtype == "formula1"` matches the vertical name).
5. **Seasonal suppression is real.** NFL produced zero hints across 6 phrasings during May offseason. Brave's vertical seems to suppress entirely when there's no recent or upcoming data. Versus NBA, which fires for league queries (playoffs ongoing) but never for team-only queries — that gap is NLU, not seasonal.
6. **Payload size routinely blows the 2 KB budget.** MLB team season was 86 KB; F1 schedule 62 KB; NHL team 56 KB. `SearchPostProcessor.MAX_PAYLOAD_BYTES` (`SearchPostProcessor.kt:15`) would need a per-vertical reducer if we ever wire rich callbacks.
7. **Billing for `/web/rich` is undocumented.** Brave's public docs do not state whether the callback counts as a separate billed query. Watch the dashboard meter during any future spike that actually ships this path.

---

## 6. What `news.results[]` gives us that `web.results[]` doesn't

| Field | `web.results[]` | `news.results[]` |
|---|---|---|
| `title`, `url`, `description` | ✅ | ✅ |
| `age` | string, reflects page-publish date (often stale) | string, reflects news-cycle freshness |
| `page_age` | sometimes | reliably present, ISO timestamp |
| `breaking: Boolean` | ❌ | ✅ — Brave-classified breaking news |
| `is_live: Boolean` | ✅ (rarely true) | ✅ — live-coverage flag |
| `meta_url.hostname` | ✅ | ✅ |
| Source mix on news queries | Aggregators, summary pages | Tier-1 outlets (BBC, NBC, Guardian, CNBC, NYT, NPR, Reuters, …) |

The `breaking` flag is the key new signal. Today the agent has no first-class concept of "this result is news, and it's hot off the press." Wiring `news.results` gives the model that hint.

---

## 7. Decision

**Adopt:** Parse `news.results[]` alongside `web.results[]` in PR #12. Prefer news hits with high freshness or `breaking == true` on news-shaped queries; fall back to web hits otherwise. No new HTTP endpoint, no callback flow, no schema changes to the Brave call site — just expand the parser.

**Defer:** `/web/rich` callback flow. Reasons:

- Two-step request shape and undocumented billing are real cost risks.
- Per-vertical schemas mean per-vertical reducers, plus the `score_table` / `competition_id` / F1 calendar quirks. That's weeks, not days.
- Empty-payload gotcha (Bruins vs Rangers, Arsenal vs Chelsea, F1 standings) means we still need web-snippet fallback anyway.
- Snippet-driven answers from `web.results` are already decent on stocks/weather/sports queries that hit Yahoo Finance / weather.com / ESPN. The marginal lift from a structured card is real but not first-priority.

**Revisit when:**

- Telemetry shows a measurable answer-quality gap on `markets_current` / `weather` / `sports_recent` queries.
- We have a UI surface (e.g., a card renderer in the chat shell) that can show structured data more effectively than a paragraph.
- Brave clarifies `/web/rich` billing.

---

## 8. Open questions / future work

1. **Re-probe NFL in regular season (Sept 2026)** to confirm whether dark-period suppression is the cause or there's a genuine NLU gap.
2. **Re-probe F1 standings / results** — the empty-payload result on those two queries was suspicious for canonical phrasings. Either a transient backend issue or a systemic gap.
3. **Brave's separate `/news/search` endpoint** is not needed for the M0-style assistant flow (we already get news on `/web/search`). May matter for long-tail news pagination if we ever build a "more news" UI.
4. **Per-team NLU canonicalization in the query rewriter** — "Lakers score" → "NBA scores today" would close the NBA gap if we ever wire `/web/rich`. Not needed for the news.results path.
5. **Recency-aware caching** — PRD §3.x already specifies 5-min TTL on time-sensitive queries; once `breaking`/`age` are surfaced, the cache key could include "is this a news-flagged result" to avoid serving stale breaking news from a cache-hit.
