package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.classifier.FallThroughReason
import com.contextsolutions.localagent.classifier.PreflightDecision
import com.contextsolutions.localagent.classifier.PreflightRouter
import com.contextsolutions.localagent.inference.GenerationEvent
import com.contextsolutions.localagent.inference.GenerationRequest
import com.contextsolutions.localagent.inference.PendingToolCall
import com.contextsolutions.localagent.inference.SamplingParams
import com.contextsolutions.localagent.inference.ToolDispatcher
import com.contextsolutions.localagent.i18n.CountryDisplay
import com.contextsolutions.localagent.i18n.StringKeys
import com.contextsolutions.localagent.i18n.Strings
import com.contextsolutions.localagent.job.InlineJobResult
import com.contextsolutions.localagent.job.InlineJobRunner
import com.contextsolutions.localagent.job.JobRepository
import com.contextsolutions.localagent.job.RunJobDetector
import com.contextsolutions.localagent.job.RunJobResolution
import com.contextsolutions.localagent.job.RunJobResolver
import com.contextsolutions.localagent.language.PreferredLanguage
import com.contextsolutions.localagent.memory.Memory
import com.contextsolutions.localagent.memory.MemoryRetriever
import com.contextsolutions.localagent.memory.RememberForgetDetector
import com.contextsolutions.localagent.preferences.DefaultSiteResolver
import com.contextsolutions.localagent.preferences.GpsCoordinates
import com.contextsolutions.localagent.preferences.SearchPreferencesRepository
import com.contextsolutions.localagent.preferences.UserLocation
import com.contextsolutions.localagent.preferences.VerticalPreferences
import com.contextsolutions.localagent.preferences.WeatherLocationResolver
import com.contextsolutions.localagent.search.RelativeTemporalDetector
import com.contextsolutions.localagent.search.SearchOutcome
import com.contextsolutions.localagent.search.SearchService
import com.contextsolutions.localagent.search.SearchSource
import com.contextsolutions.localagent.search.SearchSubtype
import com.contextsolutions.localagent.search.SearchSubtypeDetector
import com.contextsolutions.localagent.search.vertical.VerticalSearchDispatcher
import com.contextsolutions.localagent.telemetry.CounterNames
import com.contextsolutions.localagent.telemetry.LatencyNames
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import com.contextsolutions.localagent.telemetry.TelemetryCounters
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The agent layer for a single user turn. LLM-side tool calling is fully
 * disabled — every tool dispatch happens BEFORE the engine, either via
 * regex/parsers (clock, my-list, memory) or via the pre-flight classifier
 * (search). The engine just consumes a system prompt + history + an optional
 * `[SEARCH CONTEXT]` block of plain text:
 *
 *  1. **Regex short-circuits** — clock, my-list, and remember/forget commands
 *     are caught at lines ~117-174 and dispatched directly to their handlers
 *     (no LLM call at all).
 *  2. **Pre-flight (M4 / WS-8)** — call [PreflightRouter] for non-clock/my-list
 *     turns. On `FireSearch`, fetch results inline and inject them as a
 *     `[SEARCH CONTEXT]` block in the system instruction. Other decisions
 *     leave the prompt untouched.
 *  3. Build the system prompt (tools list is always empty), hand it to the
 *     engine alongside the user's message.
 *  4. Forward streamed text chunks as [AgentEvent.TokenChunk] and finalise
 *     with [AgentEvent.Done] when the engine reports the turn complete.
 *
 * [maxToolCalls] still bounds the [ToolDispatcher] callback (kept as a
 * defense-in-depth wiring) but in practice never fires because no tools are
 * registered with the engine. Marker-fallback (`runTextMarkerFallback`) is
 * also retained for the edge case where Gemma emits a `<|tool_call>` text
 * marker without registration — it should be unreachable but is cheap to
 * keep.
 */
class AgentLoop(
    private val session: InferenceSession,
    private val assembler: PromptAssembler,
    private val searchService: SearchService,
    private val preflightRouter: PreflightRouter,
    private val memoryRetriever: MemoryRetriever? = null,
    private val toolHandlers: List<ToolHandler> = emptyList(),
    private val clockIntentDetector: ClockIntentDetector = ClockIntentDetector(),
    private val myListIntentDetector: MyListIntentDetector = MyListIntentDetector(),
    private val myListCommandParser: MyListCommandParser = MyListCommandParser(),
    private val myListResponseFormatter: MyListResponseFormatter = MyListResponseFormatter(),
    private val rememberForgetDetector: RememberForgetDetector = RememberForgetDetector(),
    /**
     * PR #23 — vertical dispatcher. When non-null, FireSearch routes via
     * the subtype-specific adapter; when null, falls back to the legacy
     * direct `searchService.search()` path so tests don't have to wire
     * the dispatcher.
     */
    private val verticalDispatcher: VerticalSearchDispatcher? = null,
    private val searchPreferences: SearchPreferencesRepository? = null,
    /**
     * Bundled city → GPS-coordinate catalog. When set, FireSearch
     * resolves the user's captured city to lat/lon and passes them into
     * the dispatcher so vertical adapters can substitute `{lat}` /
     * `{lon}` into URL templates (e.g. weather.gc.ca's
     * `?coords=lat,lon`). Null for tests that don't need GPS routing —
     * the dispatcher receives `gps = null` and skips any GPS-templated
     * sources cleanly.
     */
    private val locationCatalog: com.contextsolutions.localagent.preferences.LocationCatalog? = null,
    /**
     * PR #37 — resolves the city + state/province the user names in a WEATHER
     * query to catalog coordinates (onboarding now captures country only). When
     * set, the WEATHER path resolves location from the query (then from a saved
     * location memory) instead of the stored home city, and asks the user to
     * name a city when neither yields one. Null for tests/older callers, which
     * keep the pre-PR-#37 stored-location behaviour.
     */
    private val weatherLocationResolver: WeatherLocationResolver? = null,
    /**
     * PR #37 — used to pick the weather source for the *resolved city's*
     * country (NWS for a US city, Environment Canada for a CA city, …) when it
     * differs from the onboarded country. Null falls back to the stored
     * country's configured weather sources.
     */
    private val defaultSiteResolver: DefaultSiteResolver? = null,
    /**
     * When set, WEATHER FireSearch turns render a deterministic response
     * from the parsed RSS payload instead of routing through Gemma. Null
     * for tests that want to exercise the legacy `[SEARCH CONTEXT]` →
     * LLM path. Returns null itself on unfamiliar payload shapes, so the
     * fall-through to the LLM remains the safety net for non-CA weather.
     */
    private val weatherResponseFormatter: WeatherResponseFormatter? = null,
    /**
     * When set, FINANCE FireSearch turns whose payload carries a Brave `stocks`
     * rich quote render a deterministic card instead of routing through Gemma
     * (PR #38). Returns null for fallback web-snippet payloads, so those fall
     * through to the `[SEARCH CONTEXT]` → LLM path unchanged. Null for tests
     * that exercise the legacy path.
     */
    private val stockResponseFormatter: StockResponseFormatter? = null,
    /**
     * PR #88 (#59) — the "run job <name> <keywords>" inline command. When both
     * [jobRepository] and [inlineJobRunner] are bound, a leading "run job …"
     * message resolves a job by name, runs it (desktop subprocess / relay), and
     * feeds its output to the LLM as grounding context — like the explicit
     * web-search force-fire (#43). Null on graphs without jobs → the feature is
     * inert and the message falls through to the normal path.
     */
    private val jobRepository: JobRepository? = null,
    private val inlineJobRunner: InlineJobRunner? = null,
    private val runJobDetector: RunJobDetector = RunJobDetector(),
    private val runJobResolver: RunJobResolver = RunJobResolver(),
    private val temporalDetector: RelativeTemporalDetector = RelativeTemporalDetector(),
    private val logger: (String) -> Unit = {},
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    private val counters: TelemetryCounters = NoOpTelemetryCounters,
    private val responseLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
    private val responseFilter: ResponseFilter = ResponseFilter.NoOp,
    /**
     * Localized user-facing strings for this turn (PR #96 i18n). Resolved once
     * per turn by [AgentLoopFactory] from the [StringCatalog] for
     * [responseLanguage]; defaults to [Strings.ENGLISH] so tests and callers
     * without a catalog keep producing English. Deterministic replies + the
     * formatter calls read through this; model-facing text (the system prompt,
     * tool-result payloads) stays English by design.
     */
    private val strings: Strings = Strings.ENGLISH,
    private val nowEpochMs: () -> Long = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() },
) {

    private val argumentsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(input: AgentTurnInput): Flow<AgentEvent> = channelFlow {
        // M6 Phase C — counter for the daily_inference event. Increments
        // once per user turn entering the loop (regardless of whether the
        // turn errors later). No content recorded.
        counters.increment(CounterNames.QUERIES_TOTAL)
        // PR #48 — an attached photo makes this a vision turn: the query is about
        // the image, not the web or a device tool. Image turns skip the
        // deterministic clock/my-list/memory short-circuits AND preflight/search,
        // going straight to the model. Capture it once up front.
        val hasImage = input.imageBytes != null
        logger(
            "[turn] start userMessage=\"${redact(input.userMessage)}\" hasImage=$hasImage " +
                "toolHandlers=${toolHandlers.flatMap { it.definitions }.joinToString(",") { it.name }} " +
                "searchAvailable=${searchService.isAvailable()}",
        )

        // Deterministic clock-command short-circuit (PR #11). Runs BEFORE
        // pre-flight and Gemma — when the user message unambiguously asks
        // to set/cancel/list a timer or alarm, we dispatch directly and
        // skip the LLM entirely. This avoids every Gemma reliability
        // failure observed during PR development: number-mangling
        // ("11" -> "1:1"), wrong-tool selection ("timer" chosen for a
        // wall-clock time), `<|"|>` token noise in args, partial-parse
        // field dropping, and minute-rendering glitches in responses.
        //
        // Unmatched messages fall through to the existing LLM path so
        // natural-language phrasings the parser doesn't cover ("wake me
        // up tomorrow morning", compound queries) still work.
        if (toolHandlers.isNotEmpty() && !hasImage) {
            val command = ClockCommandParser.parse(input.userMessage)
            if (command != null) {
                logger("[turn] deterministic clock command: ${command::class.simpleName}")
                runClockCommandDirect(input.userMessage, command)
                return@channelFlow
            }
            // Clock-intent detected but no specific pattern matched: emit a
            // generic guidance message and SKIP the LLM entirely. This is
            // load-bearing — every Gemma failure observed in PR
            // development happened on a clock-intent turn. Falling through
            // to the model just produces garbled time strings ("3:5:5 PM"
            // every day, "7:0:0 AM" etc.). Keeping the LLM out of the
            // clock path is the only way to guarantee a clean response.
            if (clockIntentDetector.isClockIntent(input.userMessage)) {
                logger("[turn] clock intent but unmatched parser; emitting guidance")
                emitClockGuidance(input.userMessage)
                return@channelFlow
            }
            // My List shares the same reliability constraint as the clock
            // surface (PR #15): structured CRUD over a typed schema that
            // Gemma cannot be trusted to produce. Clock runs first because
            // its verbs (`set`, `cancel`, `remind`) overlap list verbs
            // (`set priority`, `remind me to <task>`) — clock wins
            // precedence so the older, more battle-tested path stays
            // authoritative on ambiguous turns. The static guidance below
            // is the explicit no-LLM-fallback contract: an intent-but-no-
            // parse turn returns a fixed reply listing valid command
            // shapes, never falls through to Gemma.
            val myListCommand = myListCommandParser.parse(input.userMessage)
            if (myListCommand != null) {
                logger("[turn] deterministic my-list command: ${myListCommand::class.simpleName}")
                runMyListCommandDirect(input.userMessage, myListCommand)
                return@channelFlow
            }
            if (myListIntentDetector.isMyListIntent(input.userMessage)) {
                logger("[turn] my-list intent but unmatched parser; emitting guidance")
                emitMyListGuidance(input.userMessage)
                return@channelFlow
            }
        }

        // Explicit memory-command short-circuit. Mirrors the clock/my-list
        // pattern above: when the user prefixes a turn with "remember …"
        // or "forget …", dispatch a deterministic acknowledgement and
        // skip the LLM. Without this, Gemma sees "remember" + the
        // add_mylist_item tool description and reliably calls add_mylist_item with
        // the post-prefix payload as the title — wrong tool, plus the
        // model emits no follow-up text and the user gets an empty
        // assistant bubble. The actual memory write still happens
        // downstream in `MemoryExtractor.extract()` (called from
        // ChatViewModel after Done) via the same `RememberForgetDetector`
        // we're consulting here, so we don't duplicate save logic.
        val memoryCommand =
            if (hasImage) RememberForgetDetector.Command.None
            else rememberForgetDetector.classify(input.userMessage)
        if (memoryCommand !is RememberForgetDetector.Command.None) {
            logger("[turn] explicit memory command: ${memoryCommand::class.simpleName}")
            emitMemoryCommandAck(input.userMessage, memoryCommand)
            return@channelFlow
        }

        // Treat the inbound user message as the trailing turn in history.
        // PR #48 — only this current turn carries image bytes (ephemeral); prior
        // history is text-only, so the image never round-trips.
        val priorHistory = input.history
        val userMessage = ChatMessage.User(input.userMessage, imageBytes = input.imageBytes)

        val finalText = StringBuilder()
        val citationsForTurn = mutableListOf<SearchSource>()
        var toolCallsThisTurn = 0
        // Tracks the agent's view of in-progress tool messages so the final
        // turnMessages is faithful to what actually happened.
        // PR #48 — turnAppendix becomes the persisted + in-memory history that
        // FUTURE turns replay, so the user turn recorded here is text-only. The
        // image rides only on `userMessage` (used for THIS turn's prompt below),
        // never on history — keeping image input ephemeral.
        val turnAppendix = mutableListOf<ChatMessage>(
            if (hasImage) ChatMessage.User(input.userMessage) else userMessage,
        )

        // -- Memory retrieval (PRD §3.2.4) --
        // Runs BEFORE pre-flight (M5_PLAN.md §2 — sequential with retrieval
        // first). The retriever is null until M5 wiring lands; the router's
        // empty-list path reproduces M4 behavior exactly.
        val retrievedMemories: List<Memory> = memoryRetriever
            ?.retrieve(input.userMessage)
            ?.map { it.memory }
            ?: emptyList()

        // -- Pre-flight (PRD §3.2.1) --
        // The router runs the classifier, applies thresholds, and (on a
        // high-band hit) rewrites date/time relatives. We branch on its
        // decision before touching the engine: FireSearch fetches results and
        // injects them as a plain-text `[SEARCH CONTEXT]` block in the system
        // instruction; everything else leaves the prompt untouched. LLM-side
        // tool registration is disabled, so the model never sees a callable
        // tool — it just reads the injected context block.
        //
        // Clock-intent short-circuit (PR #11): the shipped classifier was
        // trained before clock tools existed and occasionally fires a search
        // for queries like "set a 5-minute timer for tea". Those are caught
        // upstream by the clock/my-list regex pre-detection (lines 117-157), so
        // pre-flight only sees messages that already fell through those
        // checks. Keep the historical skipPreflight gate as defense-in-depth
        // for any clock/my-list intent the parser couldn't pin down.
        val historyForPrompt = mutableListOf<ChatMessage>().apply {
            addAll(priorHistory)
            add(userMessage)
        }
        var searchContextBlock: String? = null
        val skipPreflight = toolHandlers.isNotEmpty() &&
            (clockIntentDetector.isClockIntent(input.userMessage) ||
                myListIntentDetector.isMyListIntent(input.userMessage))
        // PR #37 — deterministic WEATHER force-fire. The shipped classifier
        // under-fires on bare forecast queries ("what's the weather" lands in
        // the middle band → LLM, which can't know the user's location or read
        // a forecast feed). Weather is a structured surface we handle
        // deterministically, but the trigger has to be TIGHT — matching any
        // message containing "weather" wrongly hijacks general questions like
        // "what is the weather typically like in England" and answers them
        // with the saved location's forecast. So we only force WEATHER when:
        //   (a) the query names a catalog city we can resolve, OR
        //   (b) the message is a tight bare-weather phrase ("what's the
        //       weather", "weather today") — the "use my saved location" case.
        // Anything else (e.g. "...typically like in England") falls through to
        // the pre-flight classifier, which decides search-vs-LLM as usual.
        val bareWeatherRequest = BARE_WEATHER_PATTERN.matches(input.userMessage.trim())
        // PR #89 — the catalog-city branch used to force-fire WEATHER whenever the
        // message merely *mentioned* a resolvable city, so "what is the history of
        // london" served the London, ON forecast. The branch now also requires:
        //   (a) explicit weather vocabulary (SearchSubtypeDetector.mentionsWeather —
        //       reuses the same lexicon the subtype router uses), AND
        //   (b) that the question isn't about the PAST (RelativeTemporalDetector
        //       .matchesPast — the relative-temporal classifier from #38), so
        //       "what was the weather like in London last year" falls through to
        //       the LLM instead of rendering the live forecast.
        // Current/future weather still force-fires ("weather in London", "weather
        // in London tomorrow"); historical weather and bare city mentions don't.
        // The no-city "use my saved location" case stays on BARE_WEATHER_PATTERN.
        val currentWeatherForCity = SearchSubtypeDetector.mentionsWeather(input.userMessage) &&
            !temporalDetector.matchesPast(input.userMessage) &&
            weatherLocationResolver?.resolve(input.userMessage) != null
        val deterministicWeather = !skipPreflight &&
            verticalDispatcher != null &&
            weatherLocationResolver != null &&
            searchService.isAvailable() &&
            (bareWeatherRequest || currentWeatherForCity)
        // PR #48 — an image turn never fires search (the question is about the
        // photo). forceWeather is computed above but suppressed here so the
        // image branch wins. Falls through to the LLM with no [SEARCH CONTEXT],
        // so sampling stays at the warm defaults (searchContextBlock == null).
        val forceWeather = deterministicWeather && !hasImage

        // PR #88 (#59) — "run job <name> <keywords>" inline force-fire. Anchored
        // leading command, like the explicit web-search escape hatch (#43), but
        // it needs the job list (resolve a name → id) and runs a subprocess, so
        // it lives here in the AgentLoop (above pre-flight) rather than the
        // router. On a match we run the job and render its output DIRECTLY as the
        // assistant turn (markdown, no LLM) — like the deterministic WEATHER/
        // FINANCE cards (#32/#33), not search grounding: the raw output (tables,
        // links) IS the answer, and re-processing it through a 2B model would lose
        // the formatting. The output is persisted in this thread, so the user can
        // ask the LLM follow-ups about it. Not-found / failure short-circuit with
        // a deterministic message. An image turn (#48) is never a job command.
        if (!hasImage &&
            jobRepository != null &&
            inlineJobRunner != null &&
            runJobDetector.matches(input.userMessage)
        ) {
            val remainder = runJobDetector.stripPrefix(input.userMessage)
            when (val resolution = runJobResolver.resolve(remainder, jobRepository.snapshot())) {
                is RunJobResolution.NotFound -> {
                    logger("[turn] run job: no job matched \"${redact(resolution.requestedText)}\"")
                    emitJobNotFound(input.userMessage, resolution.requestedText)
                    return@channelFlow
                }
                is RunJobResolution.Match -> {
                    val job = resolution.job
                    // Empty keyword(s) → fall back to the job's saved argument.
                    val keywords = resolution.keywords.ifBlank { job.prompt }
                    logger("[turn] run job inline name=\"${job.name}\" keywordsLen=${keywords.length}")
                    send(AgentEvent.JobStarted(job.name))
                    when (val result = inlineJobRunner.run(job.id, keywords)) {
                        is InlineJobResult.Failure -> {
                            logger("[turn] run job failed: ${redact(result.message)}")
                            emitJobFailure(input.userMessage, job.name, result.message)
                        }
                        is InlineJobResult.Output -> {
                            logger("[turn] run job output → direct render len=${result.text.length}")
                            emitJobOutput(input.userMessage, result.text)
                        }
                    }
                    return@channelFlow
                }
            }
        }

        val decision = when {
            hasImage -> {
                logger("[turn] image attached — skipping preflight/search")
                PreflightDecision.FallThrough(
                    reason = FallThroughReason.MiddleBand,
                    pSearchRequired = null,
                )
            }
            skipPreflight -> {
                logger("[turn] skipping pre-flight (clock intent detected)")
                PreflightDecision.FallThrough(
                    reason = FallThroughReason.MiddleBand,
                    pSearchRequired = null,
                )
            }
            forceWeather -> {
                // Name WHY weather fired so a misfire (city mention with no weather
                // intent, e.g. "history of london") is visible in the logs, and flag
                // that this path skips the pre-flight classifier entirely.
                val trigger = if (bareWeatherRequest) {
                    "bare-weather-phrase"
                } else {
                    "catalog-city=${weatherLocationResolver?.resolve(input.userMessage)?.city}"
                }
                logger(
                    "[turn] WEATHER force-fire (trigger=$trigger) — " +
                        "bypassing pre-flight classifier; FireSearch(WEATHER)",
                )
                PreflightDecision.FireSearch(
                    originalQuery = input.userMessage,
                    rewrittenQuery = input.userMessage,
                    pSearchRequired = 1f,
                    subtype = SearchSubtype.WEATHER,
                )
            }
            else -> preflightRouter.route(input.userMessage, retrievedMemories)
        }
        when (decision) {
            is PreflightDecision.FireSearch -> {
                // PR #89 — a PAST-tense weather question ("what was the weather
                // like in London last year") can still reach here as subtype
                // WEATHER via the pre-flight temporal force-fire (#38) even though
                // the deterministic force-fire above already excluded it. The
                // WEATHER vertical only fetches the LIVE forecast and the
                // deterministic card would answer the wrong period, so downgrade
                // it to GENERAL: run a normal web search and let the LLM
                // synthesize. Current/future weather keeps the WEATHER vertical.
                val effectiveSubtype =
                    if (decision.subtype == SearchSubtype.WEATHER &&
                        temporalDetector.matchesPast(input.userMessage)
                    ) {
                        logger("[turn] past-tense weather query — routing GENERAL instead of the live-forecast vertical")
                        SearchSubtype.GENERAL
                    } else {
                        decision.subtype
                    }
                // PR #37 — WEATHER pre-resolution. Onboarding captures only the
                // country, so the city + state/province comes from this turn's
                // query (or, failing that, a saved location memory). When
                // neither yields a city we ask the user and skip search/LLM.
                // The resolved city's country also selects the national weather
                // source, so "weather in Toronto" works for a US-onboarded user.
                var weatherCity: String? = null
                var weatherLocationToRemember: String? = null
                var weatherPrefsOverride: VerticalPreferences? = null
                var weatherLocationOverride: UserLocation? = null
                var weatherGpsOverride: GpsCoordinates? = null
                if (effectiveSubtype == SearchSubtype.WEATHER && weatherLocationResolver != null) {
                    val basePrefs = searchPreferences?.snapshot() ?: VerticalPreferences()
                    val storedCountry = searchPreferences?.location()?.country
                    // Resolution order: the user's own words → the rewritten
                    // query (QueryRewriter's "where I live" possessive) → and
                    // ONLY for a tight bare-weather request ("what's the
                    // weather") the saved location memory. A query that named a
                    // place we couldn't resolve must NOT silently fall back to
                    // the saved location ("weather typically like in England").
                    //
                    // PR #89 — when the query names an AMBIGUOUS city (London ON
                    // vs London England), don't assume: disambiguate from a
                    // saved location memory, else prompt the user to be specific.
                    // The onboarded country is NOT used to break the tie.
                    var resolvedFromQuery: WeatherLocationResolver.Resolved? = null
                    var rememberFromQuery: WeatherLocationResolver.Resolved? = null
                    when (val detailed = weatherLocationResolver.resolveDetailed(input.userMessage)) {
                        is WeatherLocationResolver.Resolution.Unique -> {
                            resolvedFromQuery = detailed.resolved
                            rememberFromQuery = detailed.resolved
                        }
                        is WeatherLocationResolver.Resolution.Ambiguous -> {
                            val fromMemory = resolveWeatherLocationFromMemory(retrievedMemories, storedCountry)
                            val pick = fromMemory?.let { mem -> detailed.options.firstOrNull { it.samePlaceAs(mem) } }
                            if (pick != null) {
                                logger("[turn] weather: ambiguous city '${detailed.city}' resolved from saved location — ${pick.regionCode}/${pick.country}")
                                resolvedFromQuery = pick
                            } else {
                                logger("[turn] weather: ambiguous city '${detailed.city}' (${detailed.options.size} places), no saved-location match — prompting")
                                emitWeatherDisambiguationPrompt(input.userMessage, detailed)
                                return@channelFlow
                            }
                        }
                        WeatherLocationResolver.Resolution.None -> Unit
                    }
                    val resolved = resolvedFromQuery
                        ?: weatherLocationResolver.resolve(decision.rewrittenQuery, storedCountry)
                        ?: if (bareWeatherRequest) resolveWeatherLocationFromMemory(retrievedMemories, storedCountry) else null
                    when {
                        resolved != null -> {
                            weatherCity = resolved.city
                            weatherLocationToRemember = rememberFromQuery?.let { "I live in ${it.city}, ${it.regionName}" }
                            val weatherSources =
                                if (defaultSiteResolver != null && !resolved.country.equals(storedCountry, ignoreCase = true)) {
                                    defaultSiteResolver.defaultsFor(resolved.country).weather
                                } else {
                                    basePrefs.weather
                                }
                            weatherPrefsOverride = basePrefs.copy(weather = weatherSources)
                            weatherLocationOverride =
                                UserLocation(resolved.country, resolved.regionCode, resolved.city)
                            weatherGpsOverride = resolved.coords
                            logger(
                                "[turn] weather location city=${resolved.city} " +
                                    "region=${resolved.regionCode} country=${resolved.country} " +
                                    "lat=${resolved.coords.latitude} lon=${resolved.coords.longitude} " +
                                    "fromQuery=${resolvedFromQuery != null}",
                            )
                        }
                        bareWeatherRequest -> {
                            // "what's the weather" with no saved location — ask.
                            logger("[turn] weather: bare request, no city in query or memory — asking user")
                            emitWeatherLocationPrompt(input.userMessage)
                            return@channelFlow
                        }
                        else -> {
                            // WEATHER subtype but no resolvable city and not a
                            // bare request (e.g. classifier fired on a general
                            // "weather" question). Don't hijack it — fall
                            // through to the normal search/LLM path.
                            logger("[turn] weather subtype but no city resolved — deferring to search/LLM")
                        }
                    }
                }

                send(AgentEvent.SearchStarted(decision.rewrittenQuery))
                // PR #23 — route through vertical dispatcher when wired;
                // otherwise fall back to the legacy direct search (kept so
                // tests that don't supply a dispatcher keep working).
                val outcome = if (verticalDispatcher != null) {
                    val prefs = weatherPrefsOverride
                        ?: searchPreferences?.snapshot() ?: VerticalPreferences()
                    val location = weatherLocationOverride ?: searchPreferences?.location()
                    val gps = weatherGpsOverride ?: location?.let { loc ->
                        locationCatalog?.cityCoords(loc.country, loc.regionCode, loc.city)
                    }
                    if (weatherGpsOverride == null) {
                        if (gps != null) {
                            logger(
                                "[turn] resolved city coords city=${location?.city} " +
                                    "lat=${gps.latitude} lon=${gps.longitude}",
                            )
                        } else if (location != null) {
                            logger(
                                "[turn] no coords in catalog for " +
                                    "${location.country}/${location.regionCode}/${location.city} — " +
                                    "GPS-templated sources will be skipped",
                            )
                        }
                    }
                    verticalDispatcher.fetch(
                        subtype = effectiveSubtype,
                        query = decision.rewrittenQuery,
                        prefs = prefs,
                        location = location,
                        gps = gps,
                    )
                } else {
                    searchService.search(decision.rewrittenQuery)
                }
                send(AgentEvent.SearchCompleted(outcome))

                // Weather subtype direct path — bypass Gemma entirely.
                // Renders a deterministic chat bubble from the parsed RSS
                // entries (same pattern as runClockCommandDirect /
                // runMyListCommandDirect). On any failure (formatter
                // returns null for an unfamiliar payload shape, or the
                // outcome isn't Success), falls through to the legacy
                // [SEARCH CONTEXT] + LLM path so non-CA weather and any
                // future weather sources still get answered.
                if (effectiveSubtype == SearchSubtype.WEATHER &&
                    outcome is SearchOutcome.Success &&
                    weatherResponseFormatter != null
                ) {
                    val rendered = weatherResponseFormatter.format(
                        city = weatherCity ?: searchPreferences?.location()?.city,
                        payload = outcome.payload,
                        strings = strings,
                    )
                    if (rendered != null) {
                        val finalMsg = ChatMessage.Assistant(
                            text = rendered,
                            citations = outcome.payload.sources,
                            // Deterministic card — render plain, not markdown (PR #50).
                            renderMarkdown = false,
                        )
                        send(AgentEvent.TokenChunk(rendered))
                        send(
                            AgentEvent.Done(
                                message = finalMsg,
                                turnMessages = listOf(userMessage, finalMsg),
                                skipMemoryExtraction = true,
                                locationToRemember = weatherLocationToRemember,
                            ),
                        )
                        logger(
                            "[turn] weather direct path done " +
                                "finalTextLen=${rendered.length} " +
                                "citations=${outcome.payload.sources.size}",
                        )
                        return@channelFlow
                    } else {
                        logger("[turn] weather direct path declined — formatter returned null, falling through to LLM")
                    }
                }

                // FINANCE direct path (PR #38) — render a deterministic stock
                // card from a Brave `stocks` rich quote, bypassing Gemma. On a
                // fallback web-snippet payload the formatter returns null and we
                // fall through to the [SEARCH CONTEXT] → LLM path below.
                if (effectiveSubtype == SearchSubtype.FINANCE &&
                    outcome is SearchOutcome.Success &&
                    stockResponseFormatter != null
                ) {
                    val rendered = stockResponseFormatter.format(outcome.payload, strings)
                    if (rendered != null) {
                        val finalMsg = ChatMessage.Assistant(
                            text = rendered,
                            citations = outcome.payload.sources,
                            // Deterministic card — render plain, not markdown (PR #50).
                            renderMarkdown = false,
                        )
                        send(AgentEvent.TokenChunk(rendered))
                        send(
                            AgentEvent.Done(
                                message = finalMsg,
                                turnMessages = listOf(userMessage, finalMsg),
                                skipMemoryExtraction = true,
                            ),
                        )
                        logger(
                            "[turn] finance direct path done " +
                                "finalTextLen=${rendered.length} " +
                                "citations=${outcome.payload.sources.size}",
                        )
                        return@channelFlow
                    } else {
                        logger("[turn] finance direct path declined — no rich quote, falling through to LLM")
                    }
                }

                val subtypeLabel = effectiveSubtype.name.lowercase()
                val rawBody = when (outcome) {
                    is SearchOutcome.Success -> {
                        citationsForTurn.addAll(outcome.payload.sources)
                        outcome.payload.json
                    }
                    is SearchOutcome.Error ->
                        "error: ${outcome.kind.name} — ${outcome.message}"
                }
                // Cap the body so the whole [SEARCH CONTEXT] block lands at
                // ~3600 chars (≈ 1000 tokens). Leaves the KV cache headroom
                // we need for the rest of the system prompt + history. The
                // header + footer + query/subtype lines take a fixed ~80
                // chars; we compute the budget for the body then truncate
                // with a visible marker so the model knows it was cut.
                val wrapperOverhead =
                    "[SEARCH CONTEXT]\nquery: ${decision.rewrittenQuery}\nsubtype: $subtypeLabel\n\n[/SEARCH CONTEXT]".length
                val bodyBudget = SEARCH_CONTEXT_MAX_CHARS - wrapperOverhead
                val body = if (rawBody.length > bodyBudget) {
                    val keep = (bodyBudget - SEARCH_CONTEXT_TRUNCATION_MARKER.length).coerceAtLeast(0)
                    logger(
                        "[turn] search context body truncated " +
                            "originalLen=${rawBody.length} keptLen=$keep " +
                            "budget=$bodyBudget overhead=$wrapperOverhead",
                    )
                    rawBody.take(keep) + SEARCH_CONTEXT_TRUNCATION_MARKER
                } else {
                    rawBody
                }
                searchContextBlock = buildString {
                    append("[SEARCH CONTEXT]\n")
                    append("query: ").append(decision.rewrittenQuery).append('\n')
                    append("subtype: ").append(subtypeLabel).append('\n')
                    append(body)
                    append("\n[/SEARCH CONTEXT]")
                }
                logger(
                    "[turn] search context assembled blockLen=${searchContextBlock.length} " +
                        "outcome=${outcome::class.simpleName} subtype=$subtypeLabel",
                )
                // Full block goes to logcat verbatim (no redaction) so the
                // user can verify on-device that the LLM is seeing real
                // payload data, not just page-description snippets. At ~3600
                // chars max this fits inside logcat's per-line limit.
                logger("[turn] >>> SEARCH CONTEXT START >>>")
                logger(searchContextBlock)
                logger("[turn] <<< SEARCH CONTEXT END <<<")
            }
            is PreflightDecision.SkipSearch,
            is PreflightDecision.FallThrough,
            is PreflightDecision.SearchDisabled -> Unit // M2 path unchanged
        }

        val structured = assembler.assembleStructured(
            history = historyForPrompt,
            memoryBlock = PromptAssembler.renderMemoryBlock(retrievedMemories),
            searchContext = searchContextBlock,
            searchAvailable = searchService.isAvailable(),
            responseLanguage = responseLanguage,
        )
        val request = GenerationRequest(
            systemInstruction = structured.systemInstruction,
            history = structured.history,
            tools = structured.tools,
            // Search-grounded turns decode near-greedy so the model copies
            // figures (scores, prices) out of the [SEARCH CONTEXT] verbatim;
            // the default temperature 0.7 perturbs digits mid-number on a 2B
            // model ("110" -> "1110"). Open-chat turns keep the warm defaults.
            sampling = if (searchContextBlock != null) SamplingParams.GREEDY else null,
        )
        logger(
            "[turn] sending to engine systemPromptLen=${structured.systemInstruction.length} " +
                "historyTurns=${structured.history.size} " +
                "sampling=${request.sampling?.let { "greedy(topK=${it.topK},temp=${it.temperature})" } ?: "default"} " +
                "toolsRegistered=${structured.tools.joinToString(",") { it.name }}",
        )
        // Dump the full system instruction to logcat so the user can verify
        // on-device that the `[SEARCH CONTEXT]` block, memory block, and
        // tool-disabled directives all land correctly. The instruction can
        // exceed logcat's per-line ceiling (~4000 chars on Pixel) so we
        // chunk in 3500-char windows, tagged with their offset so the user
        // can stitch them back together if needed.
        val sys = structured.systemInstruction
        logger("[turn] >>> SYSTEM INSTRUCTION START >>> totalLen=${sys.length}")
        var sysOffset = 0
        while (sysOffset < sys.length) {
            val end = (sysOffset + SYSTEM_INSTRUCTION_LOG_CHUNK).coerceAtMost(sys.length)
            logger("[turn] sysInstr@$sysOffset: ${sys.substring(sysOffset, end)}")
            sysOffset = end
        }
        logger("[turn] <<< SYSTEM INSTRUCTION END <<<")
        // The user message goes to the engine as the trailing history entry
        // via sendMessageAsync — log it once at full length (no redact) so
        // the user can confirm what Gemma actually sees.
        val trailingUser = structured.history.lastOrNull()?.text.orEmpty()
        logger("[turn] user message to engine (len=${trailingUser.length}): $trailingUser")

        val dispatcher = ToolDispatcher { call ->
            logger(
                "[turn] tool_call name=${call.name} " +
                    "args=\"${redact(call.argumentsJson)}\"",
            )
            handleToolCall(
                call = call,
                turnAppendix = turnAppendix,
                citations = citationsForTurn,
                toolCallsSoFar = toolCallsThisTurn,
            ).also { result ->
                toolCallsThisTurn += 1
                logger("[turn] tool_result name=${call.name} resultPrefix=\"${redact(result)}\"")
            }
        }

        var errored = false
        // M6 Phase C — first-token latency starts when we hand the request
        // to the engine. Pre-flight + memory retrieval + the synthetic
        // search round-trip on FireSearch all happen BEFORE this point;
        // those are observed under their own metrics, so the user-perceived
        // "first text on screen after send()" decomposes cleanly.
        val generateStartMs = nowEpochMs()
        var firstTokenObserved = false
        // Signal that the model is about to produce text. Deterministic
        // short-circuits (weather/finance/clock/my-list/memory) returned before
        // here, so this fires only on real LLM turns — the speaker-mode
        // "working on it" cue rides on it and is therefore suppressed on the
        // fast deterministic renders.
        send(AgentEvent.GenerationStarted)
        try {
            session.generate(request, dispatcher).collect { event ->
                when (event) {
                    is GenerationEvent.TokenChunk -> {
                        if (!firstTokenObserved) {
                            counters.observeLatency(
                                LatencyNames.FIRST_TOKEN_MS,
                                nowEpochMs() - generateStartMs,
                            )
                            firstTokenObserved = true
                        }
                        // PR #10 — per-turn ResponseFilter strips disallowed
                        // scripts from streamed tokens. The buffered finalText
                        // therefore holds only allowed characters, so the
                        // ChatMessage.Assistant assembled at line ~204 (which
                        // lands in turnMessages and feeds the next turn's
                        // prompt history) carries no leaked output the model
                        // could re-prime on.
                        val filteredChunk = responseFilter.filter(event.text)
                        if (filteredChunk.isNotEmpty()) {
                            finalText.append(filteredChunk)
                            send(AgentEvent.TokenChunk(filteredChunk))
                        }
                    }
                    is GenerationEvent.Done -> Unit // finalisation happens after collect
                    is GenerationEvent.Error -> {
                        // Engine tool-parse failures occasionally land here
                        // when Gemma emits a malformed structured tool call
                        // (e.g. `<|"|>` quote tokens around array values).
                        // The error message includes the raw `<|tool_call>...`
                        // body — try the marker fallback on it before
                        // surfacing anything to the user. If that recovers a
                        // valid call, we suppress the error entirely and let
                        // the Done path build a normal message.
                        val recovered = if (toolHandlers.isNotEmpty()) {
                            runTextMarkerFallback(event.message, turnAppendix)
                        } else null
                        if (recovered != null) {
                            logger("[turn] recovered from engine error via marker fallback")
                            finalText.clear()
                            finalText.append(recovered)
                        } else {
                            errored = true
                            logger("[turn] engine error, no recovery: ${redact(event.message)}")
                            send(AgentEvent.Error(
                                strings.get(StringKeys.AGENT_ENGINE_ERROR),
                                event.cause,
                            ))
                        }
                    }
                    is GenerationEvent.FunctionCall -> Unit // legacy path; engine no longer emits these
                }
            }
        } catch (t: Throwable) {
            send(AgentEvent.Error(t.message ?: "engine error", t))
            return@channelFlow
        }

        if (errored) return@channelFlow

        // Marker-fallback: Gemma 4 E2B occasionally emits the tool-call as
        // literal text ("<|tool_call>call:list_alarms<tool_call|>") instead
        // of routing through the structured channel — the engine then
        // surfaces nothing as a structured call (toolCallsThisTurn stays 0).
        // When we detect a text-marker emit and a tool handler claims the
        // name, run the tool ourselves and replace the user-visible text
        // with a deterministically-rendered summary so the user gets a
        // useful response either way. The model's mistake stays invisible.
        if (toolCallsThisTurn == 0 && toolHandlers.isNotEmpty()) {
            val replacement = runTextMarkerFallback(
                rawText = finalText.toString(),
                turnAppendix = turnAppendix,
            )
            if (replacement != null) {
                finalText.clear()
                finalText.append(replacement)
                // No extra TokenChunk emit — the UI clears partialText on
                // Done and renders the final message from finalMessage.text,
                // so replacing finalText alone gives the user the clean
                // string. The raw marker may flicker briefly in the
                // streaming bubble but is gone by the time Done lands.
            }
        }

        // Time-format scrubbing: Gemma intermittently appends extra digits
        // when re-emitting times pulled from the tool result ("7:30 AM"
        // becomes "7:300 AM"). Different minutes trip the bug differently
        // (55 -> 55 fine, 30 -> 300 broken), so we can't preempt it on the
        // tool-output side alone. When any tool handler ran this turn,
        // post-process the response to trim impossible 3+ digit minutes
        // back to the canonical H:MM. Scoped to "tool handler ran this
        // turn" so we don't accidentally munge user prompts that legitimately
        // contain numeric strings (we still skip web_search since that path
        // doesn't go through toolHandlers).
        val anyHandlerToolFired = turnAppendix.any { msg ->
            msg is ChatMessage.Tool && toolHandlers.any { it.handles(msg.toolName) }
        }
        if (anyHandlerToolFired) {
            val cleaned = MINUTE_GLITCH_REGEX.replace(finalText.toString()) { m ->
                "${m.groupValues[1]}:${m.groupValues[2]} ${m.groupValues[3]}"
            }
            if (cleaned != finalText.toString()) {
                logger("[turn] scrubbed minute-glitch in response")
                finalText.clear()
                finalText.append(cleaned)
            }
        }

        // Strip any host-internal [SEARCH CONTEXT] scaffolding the model
        // parroted into its answer (PR #30). Cleaning finalText before the
        // message is built means both the user-visible bubble (rendered from
        // finalMessage.text on Done) and the persisted turnMessages → next
        // turn's history carry the clean text, so it can't re-prime later
        // turns. Tokens already streamed raw may briefly flicker the marker
        // before Done replaces the bubble — same accepted behavior as the
        // marker-fallback path above.
        val descaffolded = stripSearchContextScaffolding(finalText.toString())
        if (descaffolded != finalText.toString()) {
            logger("[turn] scrubbed leaked [SEARCH CONTEXT] scaffolding")
            finalText.clear()
            finalText.append(descaffolded)
        }

        // Every turn that reaches here is LLM-composed (free prose/math OR a
        // search-grounded answer synthesized from [SEARCH CONTEXT]) and renders
        // markdown (#41, PR #103): search answers routinely emit lists, bold,
        // and source links that look like raw `*`/`**` when rendered plain.
        // The deterministic cards that must stay plain — WEATHER/FINANCE/clock/
        // my-list/memory — early-return with their own renderMarkdown=false and
        // never reach this point. Markdown's two hazards over search text are
        // both handled: single-newline collapse by SoftBreakAddsNewLinePlugin,
        // and currency `$` (e.g. "$5 to $8") by LatexNormalizer's
        // digit-after-`$` guard.
        val finalMessage = ChatMessage.Assistant(
            text = finalText.toString(),
            citations = citationsForTurn.toList(),
            renderMarkdown = true,
        )
        turnAppendix.add(finalMessage)
        logger(
            "[turn] done finalTextLen=${finalMessage.text.length} " +
                "toolCallsThisTurn=$toolCallsThisTurn " +
                "responsePrefix=\"${redact(finalMessage.text)}\"",
        )
        send(AgentEvent.Done(message = finalMessage, turnMessages = turnAppendix.toList()))
    }

    private fun redact(text: String): String =
        if (text.length <= 80) text else text.take(77) + "..."

    /**
     * Looser tool-call body parser used when [GemmaToolCallBodyParser]
     * (regex-based, scalar values only) returns null. Handles two
     * Gemma-isms observed in the wild:
     *
     *  - `<|"|>` tokens that Gemma sometimes emits in place of literal `"`
     *    around string values
     *  - Array values like `days:[<|"|>mon<|"|>, <|"|>tue<|"|>]` that the
     *    scalar parser rejects
     *
     * Strategy: unescape the quote tokens, wrap bareword keys, then hand
     * the result to kotlinx.serialization's lenient JSON parser. If that
     * succeeds we have a real [ParsedEvent.ToolCall]; otherwise we give up
     * and the caller surfaces the marker as text.
     */
    /**
     * Per-field heuristic extractor. Used when the strict loose parser
     * rejects the body because Gemma sprayed stray `<|"|>` tokens or
     * empty-string entries between array elements (genuinely malformed
     * JSON, observed in production logs). Each known field is pulled
     * independently with its own regex so noise between fields can't
     * cause cascading failures.
     *
     * Covers every clock-tool field name we ship today. Returns null if
     * nothing matched.
     */
    private fun parseHeuristicToolCallBody(body: String): ParsedEvent.ToolCall? {
        val match = LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]

        val args = kotlinx.serialization.json.buildJsonObject {
            fun putInt(key: String, v: Int) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))
            fun putStr(key: String, v: String) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))
            fun putBool(key: String, v: Boolean) =
                put(key, kotlinx.serialization.json.JsonPrimitive(v))

            // Numeric scalars — hour/minute, plus the timer parts.
            HOUR_REGEX.find(rawArgs)?.let { putInt("hour", it.groupValues[1].toInt()) }
            MINUTE_REGEX.find(rawArgs)?.let { putInt("minute", it.groupValues[1].toInt()) }
            HOURS_REGEX.find(rawArgs)?.let { putInt("hours", it.groupValues[1].toInt()) }
            MINUTES_REGEX.find(rawArgs)?.let { putInt("minutes", it.groupValues[1].toInt()) }
            SECONDS_REGEX.find(rawArgs)?.let { putInt("seconds", it.groupValues[1].toInt()) }
            // Days: pull `<|"|>DAY<|"|>` patterns inside the array brackets
            // and filter to valid weekday tokens. Stray tokens between
            // elements are skipped because the regex demands a wrapped
            // word.
            DAYS_BLOCK_REGEX.find(rawArgs)?.let { m ->
                val days = DAY_TOKEN_REGEX.findAll(m.groupValues[1])
                    .map { it.groupValues[1].lowercase() }
                    .filter { it in VALID_DAY_TOKENS }
                    .toSet()
                if (days.isNotEmpty()) {
                    put("days", kotlinx.serialization.json.buildJsonArray {
                        for (d in days) add(kotlinx.serialization.json.JsonPrimitive(d))
                    })
                }
            }
            // Label: prefer the wrapped form, fall back to a quoted form.
            (LABEL_WRAPPED_REGEX.find(rawArgs) ?: LABEL_QUOTED_REGEX.find(rawArgs))?.let {
                putStr("label", it.groupValues[1].trim())
            }
            // all (bool) for cancel_* tools.
            ALL_BOOL_REGEX.find(rawArgs)?.let {
                putBool("all", it.groupValues[1].toBooleanStrict())
            }
            // id for cancel_* tools — uuid-style string after id:.
            ID_REGEX.find(rawArgs)?.let {
                putStr("id", it.groupValues[1])
            }
        }
        if (args.isEmpty()) return null
        return ParsedEvent.ToolCall(
            name,
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                args,
            ),
        )
    }

    private fun parseLooseToolCallBody(body: String): ParsedEvent.ToolCall? {
        val match = LOOSE_CALL_REGEX.matchEntire(body) ?: return null
        val name = match.groupValues[1]
        val rawArgs = match.groupValues[2]
        val unescaped = rawArgs.replace(GEMMA_QUOTE_TOKEN, "\"")
        val withQuotedKeys = BAREWORD_KEY_REGEX.replace(unescaped) { m ->
            "\"${m.groupValues[1]}\":"
        }
        val argsJson = "{$withQuotedKeys}"
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson)
            ParsedEvent.ToolCall(name, argsJson)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Scan the model's final text for a Gemma-style tool-call marker
     * (`<|tool_call>call:NAME{args}<tool_call|>`). If we find one that
     * matches a registered handler, dispatch it ourselves and return the
     * formatted summary the caller should show instead of the raw marker.
     *
     * Returns null when no marker is present, when the body can't be
     * parsed, or when no handler claims the name.
     */
    private suspend fun runTextMarkerFallback(
        rawText: String,
        turnAppendix: MutableList<ChatMessage>,
    ): String? {
        val startIdx = rawText.indexOf(TEXT_TOOL_START_MARKER)
        if (startIdx < 0) return null
        val afterStart = rawText.substring(startIdx + TEXT_TOOL_START_MARKER.length)
        val endIdx = afterStart.indexOf(TEXT_TOOL_END_MARKER)
        val body = if (endIdx >= 0) afterStart.substring(0, endIdx) else afterStart
        logger("[turn] marker fallback: raw body=\"${redact(body)}\"")
        // Three-tier parsing (most strict first):
        //
        //   1. parseLooseToolCallBody — unescape <|"|> -> ", quote bareword
        //      keys, validate as JSON. Refuses partial results, so it never
        //      drops fields silently.
        //   2. parseHeuristicToolCallBody — when JSON validation fails
        //      because Gemma sprayed stray <|"|> tokens around array
        //      elements ("days:[<|"|>mon<|"|>,<|"|>,<|"|>tue<|"|>..."),
        //      extract each known field via a targeted regex. Day tokens
        //      come from matching `<|"|>(\w+)<|"|>` inside the brackets
        //      and filtering to the seven valid weekday names. Tolerant
        //      of arbitrary noise between fields.
        //   3. GemmaToolCallBodyParser — last-resort legacy scalar parser.
        //      Does PARTIAL matching, so it can return {hour:11,minute:45}
        //      from a body that also had a malformed days array; the
        //      heuristic tier above catches more, so this only fires on
        //      genuinely-scalar bodies like set_timer.
        val parsed = parseLooseToolCallBody(body.trim())
            ?: parseHeuristicToolCallBody(body.trim())
            ?: GemmaToolCallBodyParser.parse(body.trim())
            ?: run {
                logger("[turn] marker fallback: failed to parse body=\"${redact(body)}\"")
                return null
            }
        val handler = toolHandlers.firstOrNull { it.handles(parsed.name) } ?: run {
            logger("[turn] marker fallback: no handler for ${parsed.name}")
            return null
        }
        logger("[turn] marker fallback: dispatching ${parsed.name} args=\"${redact(parsed.argumentsJson)}\"")
        val callId = "marker-fallback-0"
        turnAppendix.add(
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall(callId, parsed.name, parsed.argumentsJson),
            ),
        )
        val result = handler.execute(PendingToolCall(parsed.name, parsed.argumentsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        turnAppendix.add(
            ChatMessage.Tool(
                callId = callId,
                toolName = parsed.name,
                text = result,
                isError = isError,
            ),
        )
        val rendered = ClockResponseFormatter.format(parsed.name, result, strings)
        logger("[turn] marker fallback: rendered \"${redact(rendered)}\"")
        return rendered
    }


    private suspend fun ProducerScope<AgentEvent>.handleToolCall(
        call: PendingToolCall,
        turnAppendix: MutableList<ChatMessage>,
        citations: MutableList<SearchSource>,
        toolCallsSoFar: Int,
    ): String {
        if (toolCallsSoFar >= maxToolCalls) {
            // Record the rejected call so the conversation history is honest
            // about what happened, and tell the model to stop trying.
            val callId = "call-$toolCallsSoFar"
            turnAppendix.add(ChatMessage.Assistant(text = "", toolCall = ToolCall(callId, call.name, call.argumentsJson)))
            turnAppendix.add(
                ChatMessage.Tool(
                    callId = callId,
                    toolName = call.name,
                    text = TOOL_LIMIT_REACHED_MESSAGE,
                    isError = true,
                ),
            )
            return TOOL_LIMIT_REACHED_MESSAGE
        }

        // Pluggable handlers (clock tools, future additions). The web_search
        // path stays inline below because it emits UI events + accumulates
        // citations — coupling the agent loop owns.
        val handler = toolHandlers.firstOrNull { it.handles(call.name) }
        if (handler != null) {
            val callId = "call-$toolCallsSoFar"
            turnAppendix.add(
                ChatMessage.Assistant(
                    text = "",
                    toolCall = ToolCall(callId, call.name, call.argumentsJson),
                ),
            )
            val result = handler.execute(call)
            val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
            turnAppendix.add(
                ChatMessage.Tool(
                    callId = callId,
                    toolName = call.name,
                    text = result,
                    isError = isError,
                ),
            )
            return result
        }

        if (call.name != WEB_SEARCH_TOOL_NAME) {
            return errorPayload("Unknown tool '${call.name}'")
        }

        val query = extractQuery(call.argumentsJson)
            ?: return errorPayload("web_search arguments did not include a 'query' string")

        val callId = "call-$toolCallsSoFar"
        turnAppendix.add(
            ChatMessage.Assistant(
                text = "",
                toolCall = ToolCall(callId, call.name, call.argumentsJson),
            ),
        )
        send(AgentEvent.SearchStarted(query))
        val outcome = searchService.search(query)
        send(AgentEvent.SearchCompleted(outcome))

        return when (outcome) {
            is SearchOutcome.Success -> {
                citations.addAll(outcome.payload.sources)
                turnAppendix.add(
                    ChatMessage.Tool(
                        callId = callId,
                        toolName = call.name,
                        text = outcome.payload.json,
                        isError = false,
                    ),
                )
                outcome.payload.json
            }
            is SearchOutcome.Error -> {
                val text = "Error: ${outcome.kind.name} — ${outcome.message}"
                turnAppendix.add(
                    ChatMessage.Tool(
                        callId = callId,
                        toolName = call.name,
                        text = text,
                        isError = true,
                    ),
                )
                text
            }
        }
    }

    /**
     * Deterministic clock path: a typed [ClockCommand] from
     * [ClockCommandParser] becomes a synthetic tool call we dispatch
     * directly via the matching handler, then emit a rendered response
     * straight to the UI. Bypasses pre-flight, the engine, and Gemma
     * entirely — see the comment at the call site in [run].
     *
     * The synthetic User/Assistant(toolCall)/Tool/Assistant(final) chain
     * is appended to `turnMessages` on the Done event so the next turn's
     * history matches what the LLM path would have produced. The next
     * turn can read this history and answer follow-up questions like
     * "did that go through?" naturally.
     */
    private suspend fun ProducerScope<AgentEvent>.runClockCommandDirect(
        userMessageText: String,
        command: ClockCommand,
    ) {
        val (toolName, argsJson) = clockCommandToCall(command)
        val handler = toolHandlers.firstOrNull { it.handles(toolName) }
        if (handler == null) {
            send(AgentEvent.Error(strings.get(StringKeys.AGENT_ENGINE_ERROR), null))
            return
        }

        val callId = "deterministic-clock-0"
        val userMessage = ChatMessage.User(userMessageText)
        val toolCallMessage = ChatMessage.Assistant(
            text = "",
            toolCall = ToolCall(callId, toolName, argsJson),
        )

        val result = handler.execute(PendingToolCall(toolName, argsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        val toolResultMessage = ChatMessage.Tool(
            callId = callId,
            toolName = toolName,
            text = result,
            isError = isError,
        )

        val rendered = ClockResponseFormatter.format(toolName, result, strings)
        // Deterministic handler output — render plain, not markdown (PR #50).
        val finalMessage = ChatMessage.Assistant(text = rendered, renderMarkdown = false)

        send(AgentEvent.TokenChunk(rendered))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(
                    userMessage,
                    toolCallMessage,
                    toolResultMessage,
                    finalMessage,
                ),
                skipMemoryExtraction = true,
            ),
        )
        logger(
            "[turn] done via deterministic path tool=$toolName " +
                "finalTextLen=${rendered.length}",
        )
    }

    /**
     * Emit a generic "I didn't quite understand that clock command"
     * response and end the turn. Used when [ClockIntentDetector] thinks
     * the message is about timers/alarms but [ClockCommandParser] couldn't
     * pin it down to a specific action. Deliberately does NOT call the
     * LLM — Gemma's clock responses are unreliable enough (number
     * mangling, wrong tool, mis-rendered times) that a static guidance
     * string is strictly better UX than letting it try.
     */
    private suspend fun ProducerScope<AgentEvent>.emitClockGuidance(userMessageText: String) {
        val message = strings.get(StringKeys.CLOCK_GUIDANCE)
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * Deterministic WEATHER prompt (PR #37): the user asked about weather but
     * named no city we could resolve, and we have no saved location memory.
     * Ask for the city + state/province and skip search + the LLM (Gemma
     * mangles structured weather and can't know the user's location anyway).
     */
    private suspend fun ProducerScope<AgentEvent>.emitWeatherLocationPrompt(userMessageText: String) {
        emitWeatherText(userMessageText, strings.get(StringKeys.WEATHER_LOCATION_PROMPT))
    }

    /**
     * PR #89 — the city the user named maps to >1 catalog place (London ON vs
     * London England) and we have no saved location to disambiguate, so ask
     * which one, listing the specific options.
     */
    private suspend fun ProducerScope<AgentEvent>.emitWeatherDisambiguationPrompt(
        userMessageText: String,
        ambiguous: WeatherLocationResolver.Resolution.Ambiguous,
    ) {
        emitWeatherText(userMessageText, buildWeatherDisambiguationText(ambiguous))
    }

    /** Builds the "did you mean A or B?" text from the ambiguous options. Uses
     *  the catalog to spell out the country; falls back to the code. */
    private fun buildWeatherDisambiguationText(
        ambiguous: WeatherLocationResolver.Resolution.Ambiguous,
    ): String {
        val opts = ambiguous.options.take(MAX_DISAMBIGUATION_OPTIONS)
        val label = { r: WeatherLocationResolver.Resolved ->
            val countryName = CountryDisplay.keyFor(r.country)?.let { strings.get(it) }
                ?: locationCatalog?.country(r.country)?.name ?: r.country
            "${r.city}, ${r.regionName} ($countryName)"
        }
        val choices = when (opts.size) {
            2 -> "${label(opts[0])} or ${label(opts[1])}"
            else -> opts.dropLast(1).joinToString(", ") { label(it) } + ", or " + label(opts.last())
        }
        val example = opts.first().let { "${it.city}, ${it.regionName}" }
        return strings.get(StringKeys.WEATHER_DISAMBIGUATION, ambiguous.city, choices, example)
    }

    private suspend fun ProducerScope<AgentEvent>.emitWeatherText(userMessageText: String, message: String) {
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * PR #88 — render a successful job's output DIRECTLY as the assistant turn,
     * with NO LLM round-trip (like the WEATHER/FINANCE deterministic cards
     * #32/#33). `renderMarkdown = true` so links/tables/headers format (PR #82),
     * unlike search-grounded turns which render plain (#41). The output is
     * persisted in this thread, so the user can ask the LLM follow-ups later.
     * No `TokenChunk` is emitted: the streaming bubble is plain text, so raw
     * markdown would flash unrendered before `Done` re-renders it — instead the
     * "Running job…" chip ([AgentEvent.JobStarted]) shows until `Done` lands.
     */
    private suspend fun ProducerScope<AgentEvent>.emitJobOutput(
        userMessageText: String,
        output: String,
    ) {
        val text = output.ifBlank { strings.get(StringKeys.JOB_NO_OUTPUT) }
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = text, renderMarkdown = true)
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * Deterministic "no such job" reply for a "run job …" command that didn't
     * resolve (PR #88). No LLM. [requestedText] blank → the user gave no name.
     */
    private suspend fun ProducerScope<AgentEvent>.emitJobNotFound(
        userMessageText: String,
        requestedText: String,
    ) {
        val message = if (requestedText.isBlank()) {
            strings.get(StringKeys.JOB_RUN_PROMPT)
        } else {
            strings.get(StringKeys.JOB_NOT_FOUND, requestedText)
        }
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * Deterministic failure reply when an inline job run errors, times out, or
     * exits non-zero (PR #88). Surfaces a trimmed tail of the captured detail so
     * the user sees why, without routing the error through the LLM.
     */
    private suspend fun ProducerScope<AgentEvent>.emitJobFailure(
        userMessageText: String,
        jobName: String,
        detail: String,
    ) {
        val trimmed = detail.trim()
        val message = buildString {
            append(strings.get(StringKeys.JOB_FAILED, jobName))
            if (trimmed.isNotEmpty()) {
                append("\n\n")
                append(trimmed.take(JOB_FAILURE_DETAIL_CHARS))
            }
        }
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    /**
     * Look for the user's location in memory when the current query names no
     * city: first the already-retrieved set, then a targeted probe so a bare
     * "what's the weather?" still finds a saved "I live in …" even when the
     * weather query itself isn't semantically close to it. Returns the first
     * memory text that resolves to a catalog city.
     */
    private suspend fun resolveWeatherLocationFromMemory(
        memories: List<Memory>,
        preferredCountry: String?,
    ): WeatherLocationResolver.Resolved? {
        val resolver = weatherLocationResolver ?: return null
        for (m in memories) {
            resolver.resolve(m.text, preferredCountry)?.let { return it }
        }
        val probe = memoryRetriever?.retrieve(
            query = "the city and state or province where I live",
            threshold = 0.3,
        ).orEmpty()
        for (hit in probe) {
            resolver.resolve(hit.memory.text, preferredCountry)?.let { return it }
        }
        return null
    }

    /**
     * Deterministic My List path. Synthesises the same User → Assistant(toolCall)
     * → Tool → Assistant(final) chain that [runClockCommandDirect] does so
     * memory extraction and conversation persistence see a complete tool
     * round-trip. Bypasses pre-flight + the engine — My List is a structured
     * CRUD surface Gemma cannot drive reliably.
     */
    private suspend fun ProducerScope<AgentEvent>.runMyListCommandDirect(
        userMessageText: String,
        command: MyListCommand,
    ) {
        val (toolName, argsJson) = myListCommandToCall(command)
        val handler = toolHandlers.firstOrNull { it.handles(toolName) }
        if (handler == null) {
            send(AgentEvent.Error(strings.get(StringKeys.AGENT_ENGINE_ERROR), null))
            return
        }

        val callId = "deterministic-mylist-0"
        val userMessage = ChatMessage.User(userMessageText)
        val toolCallMessage = ChatMessage.Assistant(
            text = "",
            toolCall = ToolCall(callId, toolName, argsJson),
        )

        val result = handler.execute(PendingToolCall(toolName, argsJson))
        val isError = result.startsWith("Error:") || result.contains("\"status\":\"error\"")
        val toolResultMessage = ChatMessage.Tool(
            callId = callId,
            toolName = toolName,
            text = result,
            isError = isError,
        )

        val rendered = myListResponseFormatter.format(toolName, result, strings)
        // Deterministic handler output — render plain, not markdown (PR #50).
        val finalMessage = ChatMessage.Assistant(text = rendered, renderMarkdown = false)

        send(AgentEvent.TokenChunk(rendered))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(
                    userMessage,
                    toolCallMessage,
                    toolResultMessage,
                    finalMessage,
                ),
                skipMemoryExtraction = true,
            ),
        )
        logger(
            "[turn] done via deterministic my-list path tool=$toolName " +
                "finalTextLen=${rendered.length}",
        )
    }

    /**
     * Deterministic ack for explicit "remember …" / "forget …" turns.
     * Same pattern as [emitClockGuidance] / [emitMyListGuidance]: short
     * fixed text, no LLM, no tool call. `skipMemoryExtraction = false`
     * is load-bearing — the actual memory write lives in
     * `MemoryExtractor.extract()` downstream and consults the same
     * `RememberForgetDetector` to force-create/delete; we MUST let that
     * run so the user-visible effect (saved/forgotten) actually happens.
     */
    private suspend fun ProducerScope<AgentEvent>.emitMemoryCommandAck(
        userMessageText: String,
        command: RememberForgetDetector.Command,
    ) {
        val message = when (command) {
            is RememberForgetDetector.Command.Remember -> strings.get(StringKeys.MEMORY_ACK_REMEMBER)
            is RememberForgetDetector.Command.Forget -> strings.get(StringKeys.MEMORY_ACK_FORGET)
            RememberForgetDetector.Command.None -> return
        }
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = false,
            ),
        )
    }

    /**
     * Static guidance message for "intent detected, parser unmatched" My List
     * turns. Same reliability rationale as [emitClockGuidance] — see the
     * structural comment at the call site in [run] (lines 100–149).
     */
    private suspend fun ProducerScope<AgentEvent>.emitMyListGuidance(userMessageText: String) {
        val message = strings.get(StringKeys.MYLIST_GUIDANCE)
        val userMessage = ChatMessage.User(userMessageText)
        val finalMessage = ChatMessage.Assistant(text = message, renderMarkdown = false)
        send(AgentEvent.TokenChunk(message))
        send(
            AgentEvent.Done(
                message = finalMessage,
                turnMessages = listOf(userMessage, finalMessage),
                skipMemoryExtraction = true,
            ),
        )
    }

    private fun myListCommandToCall(command: MyListCommand): Pair<String, String> = when (command) {
        is MyListCommand.Add -> MyListToolHandler.ADD_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("title", JsonPrimitive(command.title))
                command.priority?.let { put("priority", JsonPrimitive(it.name)) }
                command.dueDateEpochMs?.let { put("due_date_epoch_ms", JsonPrimitive(it)) }
            },
        )
        is MyListCommand.Show -> MyListToolHandler.SHOW_LIST_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("include_completed", JsonPrimitive(command.includeCompleted))
            },
        )
        is MyListCommand.SetCompleted -> MyListToolHandler.COMPLETE_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("completed", JsonPrimitive(command.completed))
            },
        )
        is MyListCommand.Delete -> MyListToolHandler.DELETE_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
            },
        )
        is MyListCommand.SetPriority -> MyListToolHandler.EDIT_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("priority", JsonPrimitive(command.priority.name))
            },
        )
        is MyListCommand.SetDueDate -> MyListToolHandler.EDIT_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                command.dueDateEpochMs?.let { put("due_date_epoch_ms", JsonPrimitive(it)) }
            },
        )
        is MyListCommand.SetTitle -> MyListToolHandler.EDIT_ITEM_NAME to Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                putRef(command.ref)
                put("title", JsonPrimitive(command.title))
            },
        )
        MyListCommand.ClearCompleted -> MyListToolHandler.CLEAR_COMPLETED_NAME to "{}"
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRef(ref: MyListRef) {
        when (ref) {
            is MyListRef.Index -> put("index", JsonPrimitive(ref.oneBased))
            is MyListRef.TitleSubstring -> put("title_substring", JsonPrimitive(ref.needle))
        }
    }

    /**
     * Translates a [ClockCommand] into the (toolName, argsJson) pair the
     * [ClockToolHandler] expects. The handler's arg parsing is already
     * tolerant of integer or float JSON numbers, so we emit ints directly.
     */
    private fun clockCommandToCall(command: ClockCommand): Pair<String, String> = when (command) {
        is ClockCommand.SetTimer -> {
            ClockToolHandler.SET_TIMER_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    // Always emit `seconds` — the handler sums hours*3600 +
                    // minutes*60 + seconds, so a single field is enough and
                    // avoids losing precision on the split.
                    put("seconds", JsonPrimitive(command.totalSeconds))
                    if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.SetAlarm -> {
            ClockToolHandler.SET_ALARM_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("hour", JsonPrimitive(command.hour))
                    put("minute", JsonPrimitive(command.minute))
                    if (command.days.isNotEmpty()) {
                        put("days", buildJsonArray {
                            for (d in command.days) add(JsonPrimitive(d.name.lowercase()))
                        })
                    }
                    if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.CancelTimer -> {
            ClockToolHandler.CANCEL_TIMER_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    if (command.all) put("all", JsonPrimitive(true))
                    else if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        is ClockCommand.CancelAlarm -> {
            ClockToolHandler.CANCEL_ALARM_NAME to Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    if (command.all) put("all", JsonPrimitive(true))
                    else if (command.label != null) put("label", JsonPrimitive(command.label))
                },
            )
        }
        ClockCommand.ListTimers -> ClockToolHandler.LIST_TIMERS_NAME to "{}"
        ClockCommand.ListAlarms -> ClockToolHandler.LIST_ALARMS_NAME to "{}"
    }

    private fun extractQuery(argsJson: String): String? = try {
        val obj = argumentsJson.parseToJsonElement(argsJson).jsonObject
        (obj["query"] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    } catch (_: Throwable) {
        null
    }

    private fun errorPayload(message: String): String = "Error: $message"

    companion object {
        const val DEFAULT_MAX_TOOL_CALLS: Int = 3
        const val WEB_SEARCH_TOOL_NAME: String = "web_search"
        const val NEWS_SEARCH_TOOL_NAME: String = "news_search"
        const val WEATHER_LOOKUP_TOOL_NAME: String = "weather_lookup"
        const val SPORTS_LOOKUP_TOOL_NAME: String = "sports_lookup"
        const val FINANCE_LOOKUP_TOOL_NAME: String = "finance_lookup"

        /**
         * Maximum total length (in chars) of the assembled `[SEARCH CONTEXT]`
         * block. ≈ 3600 chars maps to roughly 1000 tokens for English text,
         * which keeps a comfortable share of the 8K KV-cache budget free for
         * the rest of the system prompt + history + the model's reply.
         * Adjusted via per-PR follow-ups; bumping it has to weigh latency +
         * cache budget against the recall gain.
         */
        const val SEARCH_CONTEXT_MAX_CHARS: Int = 3600

        /** Appended to the body when [SEARCH_CONTEXT_MAX_CHARS] forces a cut. */
        const val SEARCH_CONTEXT_TRUNCATION_MARKER: String = "\n…[truncated]"

        /** Max chars of failure detail surfaced in a "run job …" error reply (PR #88). */
        const val JOB_FAILURE_DETAIL_CHARS: Int = 1_000

        /**
         * Per-line chunk size used when dumping the system instruction to
         * logcat. Pixel's per-line ceiling is ~4068 chars; this leaves
         * headroom for the tag + level prefix logcat itself adds.
         */
        const val SYSTEM_INSTRUCTION_LOG_CHUNK: Int = 3500

        const val TOOL_LIMIT_REACHED_MESSAGE: String =
            "Error: tool call limit reached for this turn. Answer the user with what you have."

        // Gemma 4 LiteRT-LM text-emit fallback format observed in production
        // logs when the structured tool channel misfires:
        //   <|tool_call>call:web_search{query: "..."}<tool_call|>
        const val TEXT_TOOL_START_MARKER: String = "<|tool_call>"
        const val TEXT_TOOL_END_MARKER: String = "<tool_call|>"

        // Catches "H:MMM AM/PM" patterns where the minute has 3+ digits —
        // a small-model rendering glitch where Gemma appends a trailing
        // digit to a correctly-formatted time it pulled from the tool
        // result. Anchored to AM/PM so we don't touch HH:MM:SS-style
        // times or arbitrary numeric strings. Case-insensitive period.
        val MINUTE_GLITCH_REGEX: Regex = Regex(
            """(\d{1,2}):(\d{2})\d+\s*(AM|PM|am|pm|Am|Pm)""",
        )

        // Host-internal search-context scaffolding the model occasionally
        // parrots back into its OWN answer (PR #30). The `[SEARCH CONTEXT]`
        // block is injected by the host into the system instruction only; the
        // model must never emit it. In a multi-turn thread a 2B Gemma — primed
        // by the literal token in the system prompt plus a prior search-backed
        // answer sitting in history — sometimes reproduces the wrapper
        // (markers, `query:`/`subtype:` lines, even the raw results JSON) and
        // hallucinates content inside it. We strip the scaffolding lines while
        // keeping the model's prose. Vertical-agnostic: the markers are the
        // same for general/news/weather/sports/finance.
        val SEARCH_CONTEXT_MARKER_LINE: Regex = Regex(
            """^\s*\[/?SEARCH CONTEXT\]\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val SEARCH_CONTEXT_SCAFFOLD_LINE: Regex = Regex(
            """^\s*(query|subtype)\s*:.*$""",
            RegexOption.IGNORE_CASE,
        )
        // A parroted results line: a single-line JSON array of objects carrying
        // our payload keys. Compact because the host emits prettyPrint=false.
        // NOTE: the closing `\}` and `\]` MUST be escaped. Android's
        // java.util.regex.Pattern is stricter than the desktop JVM (where unit
        // tests run) and throws PatternSyntaxException on bare closing `}`/`]`,
        // which would fail this companion's static init → ExceptionInInitializerError
        // on the first turn. Matches the `\{ … \}` convention in LOOSE_CALL_REGEX.
        val SEARCH_CONTEXT_JSON_LINE: Regex = Regex(
            """^\s*\[\s*\{.*"title".*("url"|"snippet").*\}\s*\]\s*$""",
        )
        val EXCESS_BLANK_LINES_REGEX: Regex = Regex("""\n{3,}""")

        /**
         * Remove host-internal `[SEARCH CONTEXT]` scaffolding the model parroted
         * into its response, keeping the surrounding prose. No-op (returns the
         * input unchanged) unless a standalone `[SEARCH CONTEXT]` /
         * `[/SEARCH CONTEXT]` marker line is present — that guard keeps a normal
         * answer that merely contains a `query:` line untouched. Only strips the
         * `query:`/`subtype:`/JSON scaffold lines once the output is known to be
         * contaminated by a marker.
         */
        fun stripSearchContextScaffolding(text: String): String {
            val hasMarker = text.lineSequence().any { SEARCH_CONTEXT_MARKER_LINE.matches(it) }
            if (!hasMarker) return text
            val kept = text.lineSequence().filterNot { line ->
                SEARCH_CONTEXT_MARKER_LINE.matches(line) ||
                    SEARCH_CONTEXT_SCAFFOLD_LINE.matches(line) ||
                    SEARCH_CONTEXT_JSON_LINE.matches(line)
            }
            return EXCESS_BLANK_LINES_REGEX.replace(kept.joinToString("\n"), "\n\n").trim()
        }

        // Gemma's text-emit form for a string boundary inside a tool-call
        // body. Each occurrence stands in for a literal `"`; replacing
        // them lets us treat the body as conventional JSON-ish.
        const val GEMMA_QUOTE_TOKEN: String = "<|\"|>"

        // call:NAME{BODY} shape — same as GemmaToolCallBodyParser's call
        // anchor but allowed to be re-used here without exposing internals.
        val LOOSE_CALL_REGEX: Regex = Regex(
            """^\s*call\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*)\}\s*$""",
            RegexOption.DOT_MATCHES_ALL,
        )

        // Bareword keys appear as `key:value` at the start of the body or
        // immediately after a `,` or `{`. The lookbehind keeps us from
        // touching keys that already happen to be quoted strings.
        val BAREWORD_KEY_REGEX: Regex = Regex(
            """(?:^|(?<=[\{,]))\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""",
        )

        // User-facing reply text (engine error, weather prompt, clock/my-list
        // guidance) moved to the i18n catalog (PR #96): resolved via the
        // per-turn `strings` from `StringKeys.AGENT_ENGINE_ERROR`,
        // `WEATHER_LOCATION_PROMPT`, `CLOCK_GUIDANCE`, `MYLIST_GUIDANCE`.

        /**
         * Tight match for a bare "use my location" weather request — the only
         * shape allowed to fall back to the saved location memory (PR #37). It
         * is deliberately anchored (whole-message) and allows only a small set
         * of trailing words so general questions like "what is the weather
         * typically like in England" or "why is the weather so weird" do NOT
         * match (those defer to the pre-flight classifier). A query that names
         * an explicit city is handled separately (resolved directly), so this
         * pattern intentionally does not try to match "weather in <place>".
         */
        val BARE_WEATHER_PATTERN: Regex = Regex(
            "^(?:(?:what|how)(?:'?s| is)?\\s+(?:the\\s+)?)?(?:weather|forecast)(?:\\s+forecast)?" +
                "(?:\\s+(?:today|tomorrow|right\\s+now|now|outside|currently|like))?\\s*[?.!]*$",
            RegexOption.IGNORE_CASE,
        )

        // Cap the places listed in the disambiguation prompt (e.g. the many
        // Springfields) so the message stays short. PR #89.
        const val MAX_DISAMBIGUATION_OPTIONS: Int = 3

        // Per-field heuristic extractors. Each matches a single
        // `key: value` pair independently so stray tokens between fields
        // can't cause cascading failures. Kept as `val` (not const) since
        // Regex isn't a compile-time constant.
        val HOUR_REGEX: Regex = Regex("""\bhour\s*:\s*(\d+)""")
        val MINUTE_REGEX: Regex = Regex("""\bminute\s*:\s*(\d+)""")
        val HOURS_REGEX: Regex = Regex("""\bhours\s*:\s*(\d+)""")
        val MINUTES_REGEX: Regex = Regex("""\bminutes\s*:\s*(\d+)""")
        val SECONDS_REGEX: Regex = Regex("""\bseconds\s*:\s*(\d+)""")

        val DAYS_BLOCK_REGEX: Regex = Regex("""\bdays\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val DAY_TOKEN_REGEX: Regex = Regex("""<\|"\|>(\w+)<\|"\|>""")
        val VALID_DAY_TOKENS: Set<String> = setOf(
            "mon", "monday",
            "tue", "tuesday", "tues",
            "wed", "wednesday",
            "thu", "thursday", "thurs",
            "fri", "friday",
            "sat", "saturday",
            "sun", "sunday",
        )

        // Label: wrapped form `label:<|"|>X<|"|>` and bare-quoted form
        // `label:"X"`. Both must terminate before a structure char so we
        // don't accidentally grab the rest of the body.
        val LABEL_WRAPPED_REGEX: Regex = Regex("""\blabel\s*:\s*<\|"\|>([^<]+?)<\|"\|>""")
        val LABEL_QUOTED_REGEX: Regex = Regex("""\blabel\s*:\s*"([^"]+)"""")

        val ALL_BOOL_REGEX: Regex = Regex("""\ball\s*:\s*(true|false)""")
        val ID_REGEX: Regex = Regex("""\bid\s*:\s*"?([a-zA-Z0-9_\-]+)"?""")
    }
}
