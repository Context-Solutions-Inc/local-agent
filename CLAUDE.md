# Context for Claude

Auto-loaded every session. Goal: don't make a fresh-context Claude rediscover what M0–M6 already cost time to learn.

## Always read first

Before suggesting architecture, scope, or code structure:

1. `PRD.md` — product spec (locked, ground truth)
2. `PHASE1_PLAN.md` — milestone plan + per-M status
3. `SYSTEM_PROMPT.md` — system prompt construction spec
4. `docs/M0_DECISION_MEMO.md` — ratified hardware/runtime decisions

On demand (read only when the work touches that area):
- `docs/M{3,4,5,6}_PLAN.md` — per-milestone phase logs + decisions
- `docs/M{3,4,5,6}_*_HANDOFF.md` — handoff notes (deferred items, findings)
- `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` — classifier eval + weaknesses
- `CLASSIFIER_DATASETS.md` — pre-flight + memory extractor dataset spec

## Project at a glance

On-device AI assistant for Android. **Pixel 7 + Android 16 only** for Phase 1.

- **LLM runtime:** LiteRT-LM 0.12.0 (`com.google.ai.edge.litertlm:litertlm-android`). Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, 2.58 GB) — text **and** image (vision). GPU via Play Services TFLite OpenCL on Mali-G710; CPU fallback when GPU init throws. E4B ruled out by LMKD thrash on 8 GB Pixel 7. (LiteRT-LM ≥0.11 needed for the vision encoder — couples to the classifier runtime, see #40.)
- **Classifier + embedder runtime:** `com.google.ai.edge.litert:litert:2.1.5` — a *different* runtime from LiteRT-LM (see #18, #40).
- **Classifier:** shared DistilBERT-base encoder + 3 task heads, INT8 → `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB), bundled in `:androidApp/src/main/assets/`. 3 outputs/pass.
- **Embedder:** all-MiniLM-L6-v2 INT8 (23.5 MB), bundled. Mean-pool + L2-norm baked in; output is one 384-dim vector.
- **Architecture:** KMP `:shared` agent core + Compose-Multiplatform `:ui` (every screen, incl. Chat) + thin `:androidApp` / `:desktopApp` shells. DI is **Koin** (Hilt fully removed). iOS stubbed for Phase 2. The desktop port (CMP + Koin) is now on `main` — merged via PR #53 (v0.1.0); `main` is the CMP+Koin reality.
- **Toolchain:** JDK 17, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.3.21. DI is Koin 4.2.1 (Hilt + KSP both removed in the Phase-3 Hilt elimination).
- **Status:** M0–M6 complete (`PHASE1_PLAN.md` §5). M7 (closed beta → Play Store) not started. **Desktop port (Linux/macOS/Windows via llama.cpp + Compose Desktop): Phases 0–9 complete, MERGED to `main` via PR #53 (v0.1.0, merge `8963a06`, 2026-06-02); on-device-validated on Linux + Pixel 7. `v0.1.0` git tag still pending.** — see `docs/DESKTOP_PORT_PLAN.md`.

## Hard invariants

Surfaced the hard way — don't rediscover. Numbering is **stable**; code/comments reference these by number.

### Inference runtime (LiteRT-LM, Gemma)

1. **Every LiteRT-LM call must run off the main thread.** `Engine.initialize()` blocks 4–8 s and will ANR. `LiteRtInferenceEngine` wraps in `Dispatchers.IO` — keep it that way.
2. **`GenerationRequest.maxTokens` is a no-op.** No per-call cap surfaced; cancel the Flow at the parser layer to stop early.
3. **`Backend.GPU()` requires `play-services-tflite-gpu:16.4.0` at runtime.** Without it, GPU init throws `Cannot find OpenCL library`. Devices without recent Play Services fall back to CPU via `LiteRtInferenceEngine.tryInitialize`.
4. **Pixel CDEV throttling drops GPU clocks before `PowerManager.currentThermalStatus` reflects it.** For an accurate throttling signal, infer from measured tok/s drift, not the high-level API.
8. **Tool calling MUST go through LiteRT-LM's structured channel.** Register via `ConversationConfig.tools` (`tool(OpenApiTool)`). Text-only schemas in the system prompt do NOT unlock tool-use mode — the model defaults to "I don't have real-time data" refusals.
9. **Reuse the same `Conversation` across the multi-step tool-call cycle** (per https://ai.google.dev/edge/litert-lm/android): `sendMessageAsync(userText)` → if `Message.toolCalls` populated, execute tool → `sendMessageAsync(Message.tool(...))` on the **same** conversation. Re-creating per step + replaying via `initialMessages` breaks Gemma's call↔response correlation (it just re-emits the call). `LiteRtInferenceEngine.generate` drives this loop and exposes a `ToolDispatcher` callback.
10. **`Content.ToolResponse(name, response)` needs a structured payload, not a JSON string.** Gson quotes a `String`, so Gemma sees a blob instead of an array. The engine parses with kotlinx.serialization → `List<Map<String, Any?>>`. See `LiteRtInferenceEngine.parseAsStructured`.
11. **Don't pre-template `<start_of_turn>…<end_of_turn>` markers.** LiteRT-LM applies Gemma's chat template internally with `ConversationConfig` + `sendMessageAsync`. `PromptAssembler.assembleStructured` returns markers-free.
29. **`Conversation.cancelProcess()` is the ONLY way to actually stop an in-flight decode.** `Job.cancel()` closes the Flow but the native worker keeps decoding to end-of-turn, holding GPU/CPU and freezing the UI. `LiteRtInferenceEngine.bindCancellation` registers an `invokeOnCompletion` that fires `cancelProcess()` on parent cancel; the finally block calls it before `conversation.close()`. This is the only lever that recovers a GPU-saturation freeze (the M7 main-thread stall) — so the watchdog drives it: `InferenceSessionManager.forceUnload(UnloadReason.MainThreadWatchdog)` cancels in-flight `Job`s, firing this path. **`forceUnload` cancels in-flight generations ONLY for `MainThreadWatchdog`**; `LowMemory`/`TrimMemory`/`Manual` defer (app isn't hung; cutting a live turn is worse UX). Best-effort if the GPU is wedged so hard `cancelProcess()` can't return. (Discovered via `javap`; not in public docs.)

`MarkerFunctionCallParser` is legacy (pre-#8 text-marker workaround) — unused in production but still tested.

### Classifier + embedder runtime (ai-edge-litert)

12. **The classifier .tflite has 2 inputs + 3 outputs identified by `tensor.name()`, NOT interpreter index** — the runtime silently permutes both.
    - **Inputs** (`int64`, `[1,128]`): `serving_default_args_0:0`→`input_ids`, `serving_default_args_1:0`→`attention_mask`. Dispatch on the `_N:0` suffix of `getInputTensor(i).name()`.
    - **Outputs** (`float32`): `:0`→preflight `[1,3]` `[search_required, search_not_required, ambiguous]`; `:1`→presence `[1,2]` `[no_extraction, has_extraction]`; `:2`→category `[1,6]` multi-label sigmoid `[personal_identity, preference, professional, interest, relationship, temporary_context]`. Dispatch on the `:N` suffix of `getOutputTensor(i).name()`. Hardcoding indices ships a silent swap.
13. **Classifier tokenizer must match training-time `distilbert-base-uncased` exactly.** Wrong vocab/splits or a stale `vocab.txt` silently degrades the classifier with no error. Android bundles the 30,522-entry WordPiece vocab and lower-cases input. `WordPieceTokenizerFixtureTest` asserts byte-exact input_ids — re-run if you re-export.
14. **Pre-flight thresholds are CONFIGURABLE (PRD §3.2.1), shipped via `assets/preflight_config.json`, read at startup.** Currently **0.3 high / 0.15 low** — relaxed from 0.85 over time because the v1.0 classifier under-fires on weather/sports/news/finance queries the verticals handle cleanly. The asset is the runtime source of truth; `PreflightThresholds.DEFAULT` (**0.5f** in code) only fires if the asset fails to load, and `CanonicalEvalTest`'s middle-band fixture is pinned to it — bump them together. **Don't hard-code** — that blocks telemetry-driven tuning (the documented path to closing the v1.0 §7 precision gap).
15. **Classifier .tflite sequence length is statically baked at export (128).** Different lengths require `ct-export-litert --max-length N`.
16. **`ai-edge-torch` was renamed `litert-torch` (2025).** Use `import litert_torch`; the export driver falls back to `ai_edge_torch` only for old sessions. `litert_torch.generative.quantize` recipes are LLM-specific — for encoder INT8 weight-only quant use `ai_edge_quantizer.Quantizer` with `MIN_MAX_UNIFORM_QUANT`.
17. **`models/` is gitignored — model cards live in `docs/`** as `docs/preflight_memory_shared_vX.Y.Z_MODEL_CARD.md`.
18. **Pre-flight classifier MUST run on `com.google.ai.edge.litert:litert:2.x` — NOT `org.tensorflow:tensorflow-lite` and NOT Play Services TFLite.** Both classic runtimes produce silently broken output for our `ai-edge-quantizer` INT8 model (logits ~1500× reference, every query collapsing to one class — no exception). `litert:2.x` is the Android port of Python's `ai-edge-litert`, which the export tooling targets (different package `com.google.ai.edge.litert.CompiledModel`, different native libs). LiteRT-LM keeps its own runtime via `play-services-tflite-*`; both coexist. **Class-collision workaround:** the litert AAR and the transitive `tensorflow-lite-api` both bundle `org.tensorflow.lite.*`; exclude `org.tensorflow:tensorflow-lite-api` via `configurations.matching {…}.configureEach { exclude(...) }` in both `:shared` and `:androidApp` build files.

The embedder uses the same runtime (#18) and same vocab — `assets/vocab.txt` is byte-identical to bert-base-uncased's, so both engines share one tokenizer singleton via Hilt. GPU delegate refuses both graphs (`BROADCAST_TO`/`EMBEDDING_LOOKUP`/`CAST INT64→FLOAT32` unsupported); CPU XNNPACK only. Classifier p95 113 ms, embedder p95 41 ms.

### Build, Android platform, framework

5. **`:shared` uses `com.android.kotlin.multiplatform.library`**, not the old `com.android.library` + `kotlin.multiplatform` (AGP 9 forbids it). Android config goes inside `kotlin { android { } }`.
6. **WorkManager's `SystemForegroundService` needs `foregroundServiceType` merged in the manifest** (`tools:node="merge"`). Any `setForeground(...)` worker crashes with `foregroundServiceType 0x1 is not a subset of 0x0` otherwise. Currently `dataSync` for the model download.
7. **`POST_NOTIFICATIONS` must be requested at runtime on Android 13+** — manifest alone is silently insufficient. `MainActivity.ensureNotificationPermission` does this on first launch.
19. **AGP 9 KMP source-set DSL rejects the closure form for catalog `Provider` deps.** `implementation(libs.foo) { exclude(...) }` inside `kotlin { sourceSets {…} }` fails. Use configuration-level `configurations.matching { it.name.startsWith("androidMain") || … }.configureEach { exclude(...) }` outside `kotlin {}`.
20. **SQLDelight `.sqm` files go alongside `.sq` in the same package dir** (NOT a `migrations/` subdir). With `N.sqm` present, `Schema.version` = `N+1`. `verifyMigrations = true` requires a committed `.db` snapshot per prior version in `src/commonMain/sqldelight/databases/` or code-gen fails. **New-migration dance:** (a) generate `<currentVersion>.db` FIRST via `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema` while `.sq` is still old; (b) write the `.sqm`; (c) update `.sq`; (d) rebuild. `ALTER TABLE ADD COLUMN` appends, so new columns MUST be declared at the END of `CREATE TABLE` or `verifyMigrations` flags column-order drift.
21. **Hilt `@Provides` methods do NOT auto-fill default constructor params.** If a `@Provides`-constructed class has `@Inject constructor(…, dep: Foo = NoOpFoo)`, the factory MUST pass the real `Foo` or the default silently no-ops in production. (M6 Phase C hit this for `TelemetryCounters`.)
22. **For "do X whenever screen Y is visible" use `LifecycleResumeEffect(key)`, NOT `LaunchedEffect(key)`** — `LaunchedEffect` misses background→foreground (route key unchanged). Pair with `onPauseOrDispose { job?.cancel() }`. Load-bearing for the aux (classifier+embedder) eager warm-up on Chat re-entry. Gemma is NOT eagerly warmed (PR #25 reversed M6 Phase B) — loads on first `InferenceSessionManager.generate()`.
23. **Firebase SDK deps live in `:androidApp`, NOT `:shared/androidMain`.** Define the abstraction (`AnalyticsSink`, `SafeCrashReporter`) in `:shared/commonMain`; implement in `:androidApp`.

### Privacy, telemetry, redaction (M6)

24. **Firebase Crashlytics has NO `beforeSend` egress hook.** Redaction lives at every callsite behind the `SafeCrashReporter` facade; direct `FirebaseCrashlytics…recordException(...)` is a contract violation. The facade wraps in `RedactedThrowable` (keeps stack+class, scrubs message). **Never put user text in exception messages/breadcrumbs** — the redactor is defense-in-depth.
25. **Crashlytics non-fatals batch until next launch.** `sendUnsentReports()` forces upload (exposed via `SafeCrashReporter.flushPending()`). Dedupes by exception class + top-of-stack; a re-record bumps the existing issue's count.
26. **Compose `liveRegion = Polite` on growing/streaming text re-reads the whole string each update.** Do NOT put `liveRegion` on the streaming bubble. For one-shot announcements use `LocalView.current.announceForAccessibility(text)` from a `LaunchedEffect(messages.size)` firing only on growth (deprecated in API 36 but still canonical).
27. **Counter telemetry is a separate channel from text-aware loggers.** Memory pipeline classes inject BOTH a `logger: (String) -> Unit` (counts/IDs/accelerator names — logcat-safe) AND a `TelemetryCounters` (off-device). DO NOT bridge logger → telemetry. `TelemetryPayloadBuilderTest` has a canary asserting a seeded marker never appears in any payload.
28. **Logcat tags for production loggers come from the DI module, not the class** (e.g. preflight lines tag `ClassifierModule`, set in `ClassifierModule.providePreflightRouter`). Diagnostic filter: `adb logcat -s EagerWarmUp:I InferenceSessionManager:I TelemetryWorker:I ChatViewModel:I ClassifierModule:I VerticalSearch:I BraveApi:I MemoryRetriever:I MemoryExtractor:I MemoryBackupController:I AndroidRuntime:E`. `BraveApi` logs the outgoing Brave `q` and (for `/llm/context` verticals) the chunked raw response — diagnostic only, not telemetry. By #27 `MemoryRetriever` logs only on the error path; silence is normal.

### Search verticals

Routing maps each `SearchSubtype` to one adapter via `VerticalSearchDispatcherFactory` (`adapters[subtype] ?: generalAdapter`). Endpoint per vertical differs (see #37). The vertical sources/defaults live in `search_defaults.json`; the Settings Add-source dialog uses `defaultKindFor`.

30. **No STOCKS subtype** — single-instrument quotes are part of FINANCE. (Removed once FINANCE moved to the Brave `site:` path; one query like `nvidia stock price (site:bloomberg.com)` answers without a ticker round-trip.) `SearchSubtypeDetector` has no `STOCKS_PATTERN`; that vocab folds into `FINANCE_PATTERN`. No STOCKS `when` branches remain. See #33 for the deterministic quote card.
31. **SPORTS and FINANCE use the Brave `site:` path, NOT RSS.** RSS only carries recent headlines — can't answer historical/specific queries or single-instrument quotes; a `site:`-restricted web search can. `VerticalSearchDispatcherFactory` wires SPORTS to `BraveSiteFilterAdapter(subtype=…)`, FINANCE to `FinanceQuoteAdapter` (with `BraveSiteFilterAdapter` fallback, #33). `FeedAdapter` (RSS) is live for **WEATHER only** (#32). **SPORTS & FINANCE are single-source / single-chip:** one domain per country in `search_defaults.json`, wired `maxDomains=1, maxCitations=1`; `BraveSiteFilterAdapter` trims only the post-cache `payload.sources` (UI chips) via `SearchPostProcessor.limitCitations` (endpoint-agnostic). **`payload.json` (the model's `[SEARCH CONTEXT]`) keeps Brave's full top-N** — capping it starves the model. A `BRAVE_SITE_FILTER` source with zero domains issues an *unfiltered full-web* search and returns Success. **NEWS** is the exception: it maps to `NewsKindRoutingAdapter`, a composite that fans the NEWS site list out by kind — RSS/DWML/HTML/JSON → `FeedAdapter(subtype=NEWS)`, `BRAVE_SITE_FILTER` → `BraveSiteFilterAdapter` — and merges. **Anti-pollution rule:** the Brave side runs ONLY when ≥1 `BRAVE_SITE_FILTER` source exists (else the unfiltered-Success would contaminate a feed-only list). If only one side has sources its outcome passes through; when both run, chips are concat/dedup/capped at 10 and the two payloads wrap in `{"subtype":"news","query":…,"feeds":…,"web":…}`. NEWS caps citations at 10 and ships two US defaults (`apnews.com` + `reuters.com`).
32. **WEATHER renders deterministically and is NEVER sent to the LLM** (Gemma mangles numbers, can't read a feed, doesn't know location). Fetches a national source — Environment Canada RSS (CA) or `forecast.weather.gov` DWML (US, `SourceKind.DWML` + `DwmlParser`) — and `WeatherResponseFormatter` builds the bubble. **Location resolved at query time, not onboarding.** Onboarding captures **country only** (`OnboardingViewModel.saveLocation(country)`; seeds defaults). Per-query order in `AgentLoop`: (a) city+region parsed from the query via `WeatherLocationResolver` against `LocationCatalog`; else (b) a saved location memory; else (c) a deterministic prompt for city + region. The resolved city's country selects the source via `DefaultSiteResolver`. **WEATHER force-fires outside the classifier** (it under-fires on bare forecasts): triggers when the query names a resolvable catalog city OR matches the tight whole-message `BARE_WEATHER_PATTERN`. The saved-location fallback (b) is reachable ONLY via the bare pattern. When the city came from the user's words, `AgentLoop` emits `Done.locationToRemember` → consent card via `MemoryExtractor.proposeLocationMemory` (consent, not force-save; deduped). `locations.json` covers CA + 50 US states + DC with GPS coords; new cities work automatically (coordinate-driven endpoints).
33. **Single-instrument FINANCE quotes render as a deterministic card via stockanalysis.com, bypassing the LLM.** `FinanceQuoteAdapter(fallback = BraveSiteFilterAdapter)`: (1) a Brave `site:finance.yahoo.com` search resolves the ticker from the result URL's `/quote/<TICKER>/`; (2) GET `stockanalysis.com/stocks/<ticker>/` → `StockAnalysisParser` reads the minified `quote:{p,c,cp,h,l,v,h52,l52,…}` blob + `marketCap`/`peRatio`; (3) `StockResponseFormatter` renders a `"subtype":"stock_quote"` card. On ANY miss the adapter returns the Brave snippet unchanged → LLM fallback. **NOT cached** (a cached price is stale). `AgentLoop` has a FINANCE direct-render block after WEATHER. (Needs `/web/search`, not `/llm/context` — see #37.)
35. **SPORTS runs Brave's LLM Context endpoint (`/res/v1/llm/context`), not `/web/search`** — returns pre-extracted page content (prose/tables) instead of ≤200-char snippets, richer for "who won…/scores". `BraveSiteFilterAdapter` is pinned to one domain but points at a second `SearchService` (`@SportsSearch`) backed by `KtorBraveLlmContextClient`; `grounding.generic[]` is parsed by `LlmContextPostProcessor` into the same `FormattedSearchPayload`. **Load-bearing tuning (Gemma E2B mis-transcribes digits from number-dense context, e.g. "114"→"1114"):** (a) `maxUrls=1` — `site:`-pinned to one domain, extra same-domain URLs were noise; (b) `LlmContextPostProcessor` drops snippet chunks that open with `{`/`[` (structured JSON), keeping prose; (c) search-grounded turns decode greedy + drop history (#36). A deterministic score-card route (regex+render like #32/#33) is the only thing that *guarantees* correct digits — deferred (brittle for unstructured prose). The SPORTS service uses `cacheNamespace="sports:"` so an identical query can't serve a `/web/search` payload to a SPORTS turn.
38. **Relative-temporal queries force-fire search inside `PreflightRouter`** (NOT `AgentLoop` like WEATHER #32). The classifier under-fires on now-relative queries ("who won the super bowl last year" → p=0.175 → stale Gemma answer). `RelativeTemporalDetector` (`search/`) matches PAST/PRESENT/FUTURE relative phrases (topic-agnostic) and widens the gate to `pSearch > highBand || temporalDetector.matches(query)`. It lives in the router (not AgentLoop) **because temporal force-fire WANTS the rewriter + subtype detection** the WEATHER force-fire bypasses; `QueryRewriter.dateTimeRules` resolves `last/this/next` + `tomorrow` families to concrete dates, unresolved phrases ("recently") pass through. **Excludes absolute dates** ("in 2019") and bare "now" (false hits: "now that…"). `SearchDisabled` short-circuit and `RewriterAbort` still apply. False positives tracked via `preflight_temporal_force_total` (subset of `preflight_high_band_total`, only when `forceTemporal && pSearch <= highBand`). Logcat: ` forced=temporal` on the FireSearch line.
43. **An explicit "web search …" command at the START of a query force-fires search inside `PreflightRouter`** (like #38). The user's deterministic escape hatch. `ExplicitSearchDetector` (`search/`) matches an **anchored leading** command — `web search …`, `search the web/online (for) …` (web-only; NOT `google`/`look up`, which mis-fire). Anchored-at-start is the false-positive guard ("how do web search engines work" does NOT fire). Widens the gate with `forceExplicit`. **Command words are stripped** (`stripPrefix`) before classifier/rewriter/subtype see them, but **vertical routing is kept**. **Fires even when the rewriter aborts** (with `forceExplicit`, fires the stripped query verbatim — the user asked). `SearchDisabled` short-circuit still runs first. False positives tracked via `preflight_explicit_search_force_total` (mutually exclusive with temporal; explicit wins). Logcat: ` forced=explicit`. Default-constructed in `PreflightRouter` (no DI change).

### Prompt assembly

34. **The `[SEARCH CONTEXT]` block + `PREFLIGHT_NOTICE` ride on the CURRENT user turn, NOT the system instruction.** `PromptAssembler.appendSearchContext` appends to the tail `HistoryMessage`; `buildSystemInstruction` no longer takes `searchContext`. **Don't move them back into the system prompt** — that reintroduces the disable-then-enable-search bug: a 2B model anchors on its own recent refusal (adjacent to the generation point) and ignores evidence pinned at the far-front system instruction. Canonical RAG placement (most-recent) makes fresh results win. Defensive: if the tail isn't a `USER` turn, `appendSearchContext` leaves history unchanged. (On search turns prior history is dropped entirely anyway, #36 — but placement + the defensive fallback are unchanged.)
36. **Search-grounded turns drop prior history AND decode near-greedy.** Both gate on a non-blank `[SEARCH CONTEXT]` block (GENERAL/NEWS/SPORTS; WEATHER/FINANCE render deterministically, never reach the LLM). (a) **History scoping:** `PromptAssembler.assembleStructured` calls `scopeToCurrentTurn`, keeping only the trailing USER turn for THIS generation; `AgentLoop` still persists full history for follow-ups. (b) **Greedy:** `AgentLoop` sets `GenerationRequest.sampling = SamplingParams.GREEDY`; the engine builds its `SamplerConfig` from `request.sampling ?: typed.config`. `GREEDY` is `topK=1` with `temperature=1.0` — **NOT 0.0** (a 0.0 temp risks a divide-by-zero or a stochastic-fallback special-case; `topK=1` is the real lever). Why both: under default temp 0.7 with growing history a 2B model perturbs digits when copying figures. Reduces but does NOT eliminate digit errors (#35 — the deterministic card is the only guarantee). Log: `historyTurns=N sampling=greedy(topK=1,temp=1.0)|default`.
37. **GENERAL/NEWS/SPORTS run `/llm/context` (each its own URL budget + cache namespace via a per-vertical `SearchService`); FINANCE deliberately stays on `/web/search`.** `KtorBraveLlmContextClient` ctor params `maxUrls`/`maxTokens`/`maxSnippetsPerUrl` (default 1/1800/6):
    - **GENERAL** → default `SearchService`, `maxUrls=3`, `"ctx:"` (unpinned, so >1 URL helps).
    - **NEWS** → `@NewsSearch`, `maxUrls=10`, `"news:"`; `BraveSiteFilterAdapter` `maxCitations=10`, `maxDomains=3` (covers `apnews.com`+`reuters.com` + user adds). Wrapped in `NewsKindRoutingAdapter` (#31).
    - **SPORTS** → `@SportsSearch`, `maxUrls=1`, `"sports:"` (#35).
    - **FINANCE** → `@FinanceSearch`, `KtorBraveSearchClient` (`/web/search`), `"fin:"`. **Must NOT use `/llm/context`:** it returns article URLs with no `/quote/` segment, so `FinanceQuoteAdapter.resolveTicker` (#33) silently fails and every finance query falls to the snippet LLM path. So `KtorBraveSearchClient` + `SearchPostProcessor.format` are **live, not deprecated**.
    Distinct cache namespaces keep response shapes + URL budgets from mixing in the shared `SearchCacheDao` (and the `"ctx:"` prefix invalidates pre-existing un-namespaced `/web/search` rows). `VerticalSearchModule` injects the four qualified `SearchService`s into `VerticalSearchDispatcherFactory.create`. `BraveSiteFilterAdapter`/`LlmContextPostProcessor`/`limitCitations`/the greedy+scoping path are endpoint-agnostic.

### Vision / image input

39. **Image input is gated at engine init, sent on the current turn only, and bypasses preflight/search.** Vision needs `EngineConfig.visionBackend` + `maxNumImages` set at init — NOT per-request. `LiteRtInferenceEngine.newEngine` sets `visionBackend = Backend.GPU()` + `maxNumImages = 1` when `InferenceConfig.enableVision`; the chat-load default flips it true (model keyed on `modelPath`, first loader's config wins; the M0 spike uses its own `Engine`). Image is a **downscaled JPEG `ByteArray`** on `…imageBytes` fields; the engine sends `Message.user(Contents.of(Content.ImageBytes(bytes), Content.Text(text)))`. **`Content.ImageBytes`, NOT `Content.ImageFile`** — the Photo Picker returns a `content://` Uri, not a path. **Model sees trailing-turn-only:** the engine's history path is text-only and `PromptAssembler` strips `imageBytes` from non-trailing turns (re-feeding prior images would blow context). **Display persists** the JPEG as a `messages.image_bytes` BLOB (migration `5.sqm`, v5→v6) so a resumed conversation re-renders it; `UserBubble` decodes on demand via `produceState`, bounded by LazyColumn windowing. Cleanup is free via FK `ON DELETE CASCADE`. Storage ≠ what the model sees — `AgentLoop` still writes a text-only `ChatMessage.User` into in-memory history. **An image turn skips deterministic short-circuits AND preflight/search** (`AgentLoop`'s `hasImage` gate → `FallThrough(MiddleBand)`, `forceWeather` suppressed, warm sampling). UI: Android Photo Picker (`PickVisualMedia`, no permission) → `ImagePreprocessor` decodes + downscales longest edge to ~768 px via `ImageDecoder` (applies EXIF) + JPEG-encodes; no Coil. `StubInferenceEngine` echoes `[stub] received image of N bytes`. **Benign Pixel 7 noise:** native `litert: No dispatch library found / Failed to initialize Dispatch API` is LiteRT-LM probing for an absent NPU delegate; it falls back to GPU and succeeds. Can't be silenced from app code (native) — mute at logd: `adb shell setprop log.tag.litert SILENT` or filter `adb logcat litert:S`.
40. **LiteRT-LM and the classifier runtime ship colliding `libLiteRt.so` — pin BOTH to `litert:2.1.5`, and force litert's copy to win via project-local jniLibs, never bump independently.** LiteRT-LM ≤0.10.2 statically linked its core (isolated — the basis of #18); 0.11.0+ ships standalone `libLiteRt.so` + `libLiteRtClGlAccelerator.so` sharing a name with `com.google.ai.edge.litert`'s libs. Android packages one `.so` per name and the two builds are never byte-identical. **They are NOT interchangeable:** (a) LiteRT-LM's `libLiteRt.so` lacks the `Java_com_google_ai_edge_litert_*` JNI symbols → loading it crashes the classifier with `UnsatisfiedLinkError: Environment.nativeCreate`; (b) `litert:2.1.4`'s libs SIGSEGV `liblitertlm_jni.so` 0.12.0 in GPU init. **`litert:2.1.5` is the one combination that serves both** — its `libLiteRt.so` is a superset that also drives `litertlm_jni`, so a single shared copy is correct as long as it's litert's. **`pickFirst` alone is NOT enough — it does NOT reliably pick litert's copy.** The cross-dependency native-merge order is non-deterministic (declaration order has no effect; debug and release picked *different* winners), so the old "alphabetically first" assumption was wrong — and moving these deps into `:shared` during the desktop port silently flipped the debug winner to litertlm's broken copy. **The fix (deterministic):** the `extractLitertJni` Copy task in `androidApp/build.gradle.kts` pulls litert's two natives from its own AAR (resolved version, arm64-v8a) into a generated `jniLibs.srcDir`; **project-local jniLibs always beat dependency-provided libs in `pickFirst`**, so litert's copies win every build and track the resolved litert version (no stale vendored binary on a bump). Keep the `packaging { jniLibs { pickFirsts += ["**/libLiteRt.so", "**/libLiteRtClGlAccelerator.so"] } }` block — it's what lets the project copy override the two dependency copies instead of failing the merge. **Compile-only `assembleDebug` will NOT catch a regression here — it's a native-packaging fault; re-verify on-device, or check the merged `libLiteRt.so` exports `Environment_nativeCreate` (`nm -D`).** So `litertlm` and `aiEdgeLitert` versions are **coupled** — bumping LiteRT-LM means re-checking the litert version satisfies both the classifier and the LLM on-device.

### Markdown / LaTeX rendering

41. **Markdown + LaTeX render ONLY for answers the model composes freely; search-grounded and deterministic turns render plain — gated by a persisted `renderMarkdown` flag.** Finalized assistant text renders via Markwon + `io.noties.markwon:ext-latex` (jlatexmath — native canvas, offline, **NO WebView**) in an `AndroidView` TextView (`MarkdownMathText`); the still-streaming partial keeps plain Compose `Text` (per-token reparse would jank). `renderMarkdown` lives on `ChatMessage.Assistant`/`UiMessage.Assistant`, persisted as `messages.render_markdown` (migration `6.sqm` → v7), and is **false** for WEATHER/FINANCE cards, the clock/todo/weather-prompt/memory-ack handlers, AND every search-grounded LLM turn. Search signal: `searchContextBlock != null || citationsForTurn.isNotEmpty()`. Reason: markdown reflow mangles search results (soft-break collapses newlines; `$` reads as a math delimiter) and the value is verbatim figures/citations. **ext-latex 4.6.2 matches `$$…$$` only**; the model emits single-`$`, so `LatexNormalizer.normalize` rewrites `$…$`/`\(…\)`/`\[…\]` → `$$…$$` with a math-vs-currency heuristic (leaves "$5 and $10" alone). `MarkwonInlineParserPlugin` + `SoftBreakAddsNewLinePlugin` registered. The TextView mirrors `bodyMedium` exactly (size, `lineHeight` 20sp, `letterSpacing` 0.25sp, `includeFontPadding=false`, color = `LocalContentColor.current`). All assistant turns share one `AssistantBubble`; `renderMarkdown` only swaps the inner content. **Dev-build identity:** `assembleDebug`/`installDebug` finalize with `printBuildIdentity` and `BuildConfig.GIT_DESCRIBE` shows in the About dialog — so a stale install is obvious (versionCode = HEAD's commit timestamp, unchanged for working-tree edits).

### Voice I/O — dictation (STT) + read-aloud (TTS)

42. **Voice I/O is entirely `:androidApp` (no `:shared` seam), behind `ChatSpeaker`/`SpeechDictation`** (consumers are already Android-only). Input row order: **mic · speaker · photo · Send**.
    - **Read-aloud fires ONLY at `AgentEvent.Done`, never on streaming tokens.** `ChatViewModel` speaks `MarkdownToPlainText.strip(message.text)` (citations are a separate field, so excluded); `AndroidTtsSpeaker.speak` uses `QUEUE_FLUSH`.
    - **The "working on it" ack + 5 s "still working" heartbeat gate on `AgentEvent.GenerationStarted`, NOT `send()`.** `AgentLoop` emits `GenerationStarted` right before `session.generate` — deterministic short-circuits return BEFORE it, so cues are suppressed on fast cards while covering plain/search/image LLM turns. **Don't move the ack into `send()`** (speaks over the instant cards). Heartbeat (`startWorkingTicker`) runs only GenerationStarted→Done. `AgentEvent` is a sealed interface — a new variant breaks the exhaustive `when` in `ChatViewModel.onAgentEvent` (the only one).
    - **Mic dictation is a continuous toggle, session-only.** `SpeechRecognizer` is single-shot, so `SpeechDictation` self-restarts on every result/timeout (restart on all errors except `ERROR_INSUFFICIENT_PERMISSIONS`, 150 ms debounce). Needs `RECORD_AUDIO` (runtime-requested) + a `<queries>` `RecognitionService` manifest entry (Android 11+). **Defaults OFF each launch, NOT persisted** — never auto-open the mic at startup. (The speaker toggle IS persisted via `TtsPreferences`.) There is no "microphone on" voice command (nothing would be listening).
    - **Echo suppression uses a grace tail, not instantaneous `isSpeaking`.** During read-aloud the mic stays listening in command-only mode (so "speaker off" can interrupt) — NOT paused. Non-command text is dropped while `suppressDictationText` = `ttsSpeaking` + a 2.5 s trailing grace (the recognizer delivers transcripts a beat after end-of-speech; the grace also bridges heartbeat gaps). Voice interruption is best-effort; the button is the guaranteed stop.
    - **Voice commands match the WHOLE utterance** (`VoiceCommand.match`, case/punct/whitespace-insensitive): send/cancel/clear/new chat/microphone off/speaker off/speaker on. Relies on the recognizer's pause segmentation so a command word inside a sentence stays dictation text. SEND reuses the Send button's guard; speaker on/off route through idempotent `setTtsEnabled`.
    - **TTS voice/rate/pitch is the OS setting — deliberately no in-app picker.** `AndroidTtsSpeaker` only sets `language = Locale.getDefault()`.

## Build & run

```bash
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
./gradlew :androidApp:installDebug -PuseStubEngine=true   # dev without real Gemma (StubInferenceEngine)

# Launch / M0 spike harness
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity
```

**Skip the Gemma download during dev** (production read path: `filesDir/models/gemma-4-E2B-it.litertlm`, written by `ModelDownloadWorker`):

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as com.contextsolutions.mobileagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'
```

**Wireless adb** (Pixel 7's USB is unstable — pair once, re-`connect` after each reboot; the port changes, the pairing persists):

```bash
adb pair <phone-ip>:<pairing-port>     # one-time, takes the 6-digit code
adb connect <phone-ip>:<connect-port>  # after reboot or WiFi drop
```

**Classifier training pipeline (M3/M4/WS-14):**

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"        # gen, review, dedup, stats CLIs
pip install -e ".[training]"   # adds torch/transformers/litert-torch (CUDA)
```

Reproduce v1.0 (~10 min on RTX 5090): `ct-train-classifier` → `ct-eval-classifier` → `ct-export-litert`. Gen is Ollama-backed (`qwen3.5:9b`); set `CT_GEN_BACKEND=claude` + `ANTHROPIC_API_KEY` for the Claude path. Full CLI list in `classifier-training/pyproject.toml`.

WS-14 regression gate (before any new `.tflite` lands in `models/`) — exit 0 PASS / 1 SHA-mismatch / 2 regression / 3 infra:

```bash
ct-regression-check --ckpt ../eval/runs/<ts>/best.pt
ct-regression-check --skip-eval --ckpt path/to/metrics.json   # hosted-CI flow
```

**Instrumentation tests (Pixel 7):**

```bash
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.classifier.ClassifierLatencyBenchmark
```

Other classes: `ClassifierEndToEndTest`, `EmbedderEndToEndTest`, `MemoryRetrievalLatencyBenchmark`.

**Secrets:** `secrets.properties` lives at `android-app/secrets.properties` (next to `settings.gradle.kts`), NOT the repo root — Gradle's `rootProject` is `android-app/`. See `android-app/secrets.properties.example`.

## Working norms

- **Don't commit unless explicitly asked.** The user reviews diffs and decides.
- **Read the official integration guide BEFORE introspecting the JAR.** `https://ai.google.dev/edge/litert-lm/android` documents intended usage that can't be reverse-engineered from signatures (M2 burned iterations replaying history through `initialMessages` for want of the "reuse the same Conversation" guidance). Then `javap -cp <jar> -p com.google.ai.edge.litertlm.X` for exact signatures.
- **Keep all LiteRT-LM types behind `LiteRtInferenceEngine`.** The `InferenceEngine` interface in `commonMain` is the seam; don't import `com.google.ai.edge.litertlm.*` elsewhere.
- **Documentation hygiene:** when a Phase 1 decision changes, update both `PHASE1_PLAN.md` and `M0_DECISION_MEMO.md`.
- **Brief responses.** The user reads diffs and code; don't recap them in prose.
