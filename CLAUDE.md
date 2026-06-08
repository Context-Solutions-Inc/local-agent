# Context for Claude

Auto-loaded every session. Goal: don't make a fresh-context Claude rediscover what M0–M6 already cost time to learn.

## Always read first

Before suggesting architecture, scope, or code structure:

1. `PRD.md` — product spec (locked, ground truth)
2. `PHASE1_PLAN.md` — milestone plan + per-M status
3. `SYSTEM_PROMPT.md` — system prompt construction spec
4. `docs/M0_DECISION_MEMO.md` — ratified hardware/runtime decisions

On demand (when the work touches that area):
- `docs/M{3,4,5,6}_PLAN.md` / `docs/M{3,4,5,6}_*_HANDOFF.md` — per-milestone logs, decisions, deferred items
- `docs/OLLAMA_REMOTE_LLM_PLAN.md` — remote Ollama chat LLM (PR #56, see #44)
- `docs/DESKTOP_PORT_PLAN.md` / `docs/DESKTOP_LLAMA_SERVER_PLAN.md` — desktop port + LLM runtime
- `docs/JOBS_PLAN.md` §0 — jobs feature as-built (#49–52)
- `docs/DESKTOP_PACKAGING.md` — desktop build/install/headless deploy (#53)
- `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` — classifier eval + weaknesses
- `CLASSIFIER_DATASETS.md` — pre-flight + memory extractor dataset spec

## Project at a glance

On-device AI assistant. **Pixel 7 + Android 16 only** for Phase 1; desktop (Linux/macOS/Windows) shipped via the Compose port. iOS stubbed for Phase 2.

- **Architecture:** KMP `:shared` agent core + Compose-Multiplatform `:ui` (every screen incl. Chat) + thin `:androidApp` / `:desktopApp` shells. DI is **Koin** (Hilt + KSP fully removed). `main` is the CMP+Koin reality.
- **Toolchain:** JDK 17, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.3.21, Koin 4.2.1.
- **LLM runtime (Android):** LiteRT-LM 0.12.0 (`com.google.ai.edge.litertlm:litertlm-android`). Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, 2.58 GB) — text + image. GPU via Play Services TFLite OpenCL on Mali-G710; CPU fallback. E4B ruled out (LMKD thrash on 8 GB). LiteRT-LM ≥0.11 needed for the vision encoder (couples to classifier runtime, #40).
- **LLM runtime (Desktop):** llama.cpp's `llama-server` as a localhost-HTTP subprocess (`LlamaServerInferenceEngine`, NOT a JNI binding). Gemma 4 E2B GGUF (`unsloth/gemma-4-E2B-it-GGUF` Q4_K_M) + mmproj-F16; text + image via `/v1/chat/completions` → `mtmd`. Prebuilt server pinned (`LlamaServerRelease.TAG`), downloaded first-run, CPU or **Vulkan** variant.
- **Remote LLM (both platforms, optional):** point the *chat LLM* at a remote server (Settings → **Remote LLM Connection**): **Ollama** (default, `host:port`, plain HTTP, optional SSL) **or generic OpenAI-compatible** (PR #73 — OpenAI/OpenRouter/LM Studio/vLLM/llama-server/LocalAI; full base URL incl. path, always HTTPS, API key required). `RoutingInferenceEngine` (commonMain) wraps the local engine, routes to `OllamaInferenceEngine` (pure-Ktor, OpenAI-compatible `/v1/chat/completions`, text + image) when **active** (configured + on/off switch on) + reachable, else local. Classifier/embedder/search/memory always stay on-device. See #44.
- **Classifier + embedder runtime:** `com.google.ai.edge.litert:litert:2.1.5` — a *different* runtime from LiteRT-LM (#18, #40).
- **Classifier:** shared DistilBERT-base encoder + 3 task heads, INT8 → `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB), bundled in `:androidApp/src/main/assets/`. 3 outputs/pass.
- **Embedder:** all-MiniLM-L6-v2 INT8 (23.5 MB), bundled. Mean-pool + L2-norm baked in; one 384-dim vector out.
- **Status:** M0–M6 complete (`PHASE1_PLAN.md` §5). M7 (closed beta → Play Store) not started. Desktop port + llama-server rework + remote-Ollama all merged to `main`. Detailed PR history lives in auto-memory (`MEMORY.md`), not here.

## Hard invariants

Surfaced the hard way — don't rediscover. Numbering is **stable**; code/comments reference these by number.

### Inference runtime (LiteRT-LM, Gemma)

1. **Every LiteRT-LM call must run off the main thread.** `Engine.initialize()` blocks 4–8 s and will ANR. `LiteRtInferenceEngine` wraps in `Dispatchers.IO`.
2. **`GenerationRequest.maxTokens` is a no-op.** No per-call cap; cancel the Flow at the parser layer to stop early.
3. **`Backend.GPU()` requires `play-services-tflite-gpu:16.4.0` at runtime.** Without it GPU init throws `Cannot find OpenCL library`; old Play Services fall back to CPU via `LiteRtInferenceEngine.tryInitialize`.
4. **Pixel CDEV throttling drops GPU clocks before `PowerManager.currentThermalStatus` reflects it.** Infer throttling from measured tok/s drift, not the high-level API.
8. **Tool calling MUST go through LiteRT-LM's structured channel** — register via `ConversationConfig.tools` (`tool(OpenApiTool)`). Text-only schemas in the system prompt do NOT unlock tool-use mode (model defaults to "I don't have real-time data" refusals).
9. **Reuse the same `Conversation` across the multi-step tool-call cycle** (per https://ai.google.dev/edge/litert-lm/android): `sendMessageAsync(userText)` → if `Message.toolCalls` populated, run tool → `sendMessageAsync(Message.tool(...))` on the **same** conversation. Re-creating per step + replaying via `initialMessages` breaks Gemma's call↔response correlation. `LiteRtInferenceEngine.generate` drives this loop via a `ToolDispatcher` callback.
10. **`Content.ToolResponse(name, response)` needs a structured payload, not a JSON string.** Gson quotes a `String` → Gemma sees a blob. Engine parses with kotlinx.serialization → `List<Map<String, Any?>>` (`LiteRtInferenceEngine.parseAsStructured`).
11. **Don't pre-template `<start_of_turn>…<end_of_turn>` markers.** LiteRT-LM applies Gemma's chat template internally. `PromptAssembler.assembleStructured` returns markers-free.
29. **`Conversation.cancelProcess()` is the ONLY way to stop an in-flight decode.** `Job.cancel()` closes the Flow but the native worker decodes to end-of-turn, holding GPU/CPU and freezing the UI. `LiteRtInferenceEngine.bindCancellation` fires `cancelProcess()` on parent cancel (finally block, before `conversation.close()`), driven by the watchdog: `InferenceSessionManager.forceUnload(UnloadReason.MainThreadWatchdog)`. **`forceUnload` cancels in-flight generations ONLY for `MainThreadWatchdog`**; `LowMemory`/`TrimMemory`/`Manual` defer (cutting a live turn is worse UX). Best-effort if the GPU is wedged. (Not in public docs.)
44. **The remote-LLM path lives entirely behind the `InferenceEngine` seam — never special-case it above the seam.** `RoutingInferenceEngine` (`single<InferenceEngine>`, both platforms) sits **below** Android `InferenceSessionManager` / desktop `WarmModel` and decides backend **once per `loadModel`** (active+reachable→remote, else local), tagging the `RoutedHandle`; agent loop/session/service/watchdogs unchanged. Resident handle drops (→ next turn re-decides) on (a) Settings change (`OllamaPreferences.configFlow()`) and (b) `OllamaConnectionMonitor` offline/online. Two backend types via `OllamaConfig.serverType` (`OLLAMA`/`OPENAI`), both behind the same `OllamaInferenceEngine`/`OllamaClient`/`OllamaPreferences` names (extend, don't rename); gate is **`isActive = enabled && isConfigured`**. **Full rule — URL shapes, SSL, `keep_alive`, API-key, on/off switch, diagnostics, `Accelerator.REMOTE`, cleartext, un-timed stream: `docs/OLLAMA_REMOTE_LLM_PLAN.md` (+ its PR #73 section).**

`MarkerFunctionCallParser` is legacy (pre-#8 text-marker workaround) — unused in production but still tested.

### Classifier + embedder runtime (ai-edge-litert)

12. **The classifier .tflite has 2 inputs + 3 outputs identified by `tensor.name()`, NOT interpreter index** (runtime silently permutes both).
    - **Inputs** (`int64 [1,128]`): `serving_default_args_0:0`→`input_ids`, `serving_default_args_1:0`→`attention_mask`. Dispatch on the `_N:0` suffix of `getInputTensor(i).name()`.
    - **Outputs** (`float32`): `:0`→preflight `[1,3]` `[search_required, search_not_required, ambiguous]`; `:1`→presence `[1,2]` `[no_extraction, has_extraction]`; `:2`→category `[1,6]` multi-label sigmoid `[personal_identity, preference, professional, interest, relationship, temporary_context]`. Dispatch on the `:N` suffix of `getOutputTensor(i).name()`.
13. **Classifier tokenizer must match training-time `distilbert-base-uncased` exactly.** Wrong/stale vocab silently degrades it with no error. Android bundles the 30,522-entry WordPiece vocab, lower-cases input. `WordPieceTokenizerFixtureTest` asserts byte-exact input_ids — re-run if you re-export.
14. **Pre-flight thresholds are CONFIGURABLE (PRD §3.2.1) via `assets/preflight_config.json`, read at startup.** Currently **0.3 high / 0.15 low** (relaxed from 0.85 — v1.0 under-fires on weather/sports/news/finance the verticals handle). The asset is the runtime source of truth; `PreflightThresholds.DEFAULT` (**0.5f**) only fires if the asset fails to load, and `CanonicalEvalTest`'s middle-band fixture is pinned to it — bump together. **Don't hard-code** (blocks telemetry-driven tuning).
15. **Classifier .tflite sequence length is statically baked at export (128).** Different lengths need `ct-export-litert --max-length N`.
16. **`ai-edge-torch` was renamed `litert-torch` (2025).** `import litert_torch` (driver falls back to `ai_edge_torch` for old sessions). `litert_torch.generative.quantize` is LLM-specific — for encoder INT8 weight-only quant use `ai_edge_quantizer.Quantizer` with `MIN_MAX_UNIFORM_QUANT`.
17. **`models/` is gitignored — model cards live in `docs/`** as `docs/preflight_memory_shared_vX.Y.Z_MODEL_CARD.md`.
18. **Pre-flight classifier MUST run on `com.google.ai.edge.litert:litert:2.x` — NOT `org.tensorflow:tensorflow-lite`, NOT Play Services TFLite.** Both classic runtimes silently produce broken output for our `ai-edge-quantizer` INT8 model (logits ~1500× reference, every query collapses to one class, no exception). `litert:2.x` is the Android port of Python's `ai-edge-litert` (`com.google.ai.edge.litert.CompiledModel`, different native libs). LiteRT-LM keeps its own runtime via `play-services-tflite-*`; both coexist. **Class-collision workaround:** litert AAR + transitive `tensorflow-lite-api` both bundle `org.tensorflow.lite.*` — exclude `org.tensorflow:tensorflow-lite-api` via `configurations.matching {…}.configureEach { exclude(...) }` in both `:shared` and `:androidApp`.

Embedder uses the same runtime (#18) + same vocab (`assets/vocab.txt` byte-identical to bert-base-uncased's); both engines share one tokenizer singleton. GPU delegate refuses both graphs (`BROADCAST_TO`/`EMBEDDING_LOOKUP`/`CAST INT64→FLOAT32` unsupported); CPU XNNPACK only. Classifier p95 113 ms, embedder p95 41 ms.

### Build, Android platform, framework

5. **`:shared` uses `com.android.kotlin.multiplatform.library`**, not `com.android.library` + `kotlin.multiplatform` (AGP 9 forbids it). Android config goes inside `kotlin { android { } }`.
6. **WorkManager's `SystemForegroundService` needs `foregroundServiceType` merged in the manifest** (`tools:node="merge"`), else any `setForeground(...)` worker crashes (`foregroundServiceType 0x1 is not a subset of 0x0`). Currently `dataSync` for the model download.
7. **`POST_NOTIFICATIONS` must be requested at runtime on Android 13+** (manifest alone is insufficient). `MainActivity.ensureNotificationPermission` on first launch.
19. **AGP 9 KMP source-set DSL rejects the closure form for catalog `Provider` deps.** `implementation(libs.foo) { exclude(...) }` inside `kotlin { sourceSets {…} }` fails — use configuration-level `configurations.matching { … }.configureEach { exclude(...) }` outside `kotlin {}`.
20. **SQLDelight `.sqm` files go alongside `.sq` in the same package dir** (NOT a `migrations/` subdir). With `N.sqm` present, `Schema.version` = `N+1`. `verifyMigrations = true` needs a committed `.db` snapshot per prior version in `src/commonMain/sqldelight/databases/`. **New-migration dance:** (a) generate `<currentVersion>.db` FIRST via `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema` while `.sq` is still old; (b) write `.sqm`; (c) update `.sq`; (d) rebuild. `ALTER TABLE ADD COLUMN` appends, so new columns MUST be declared at the END of `CREATE TABLE` or `verifyMigrations` flags column-order drift.
21. **Hilt `@Provides` methods do NOT auto-fill default constructor params.** A `@Provides`-constructed class with `@Inject constructor(…, dep: Foo = NoOpFoo)` MUST pass the real `Foo` or the default silently no-ops in production. (M6 Phase C, `TelemetryCounters`.)
22. **For "do X whenever screen Y is visible" use `LifecycleResumeEffect(key)`, NOT `LaunchedEffect(key)`** (the latter misses background→foreground, route key unchanged). Pair with `onPauseOrDispose { job?.cancel() }`. Load-bearing for aux (classifier+embedder) eager warm-up on Chat re-entry. Gemma is NOT eagerly warmed (PR #25 reversed M6 Phase B) — loads on first `InferenceSessionManager.generate()`.
23. **Firebase SDK deps live in `:androidApp`, NOT `:shared/androidMain`.** Define abstractions (`AnalyticsSink`, `SafeCrashReporter`) in `:shared/commonMain`; implement in `:androidApp`.

### Privacy, telemetry, redaction (M6)

24. **Firebase Crashlytics has NO `beforeSend` egress hook.** Redaction lives at every callsite behind the `SafeCrashReporter` facade; direct `FirebaseCrashlytics…recordException(...)` is a contract violation. The facade wraps in `RedactedThrowable` (keeps stack+class, scrubs message). **Never put user text in exception messages/breadcrumbs.**
25. **Crashlytics non-fatals batch until next launch.** `sendUnsentReports()` forces upload (`SafeCrashReporter.flushPending()`). Dedupes by exception class + top-of-stack.
26. **Compose `liveRegion = Polite` on growing/streaming text re-reads the whole string each update.** Do NOT put `liveRegion` on the streaming bubble. For one-shot announcements use `LocalView.current.announceForAccessibility(text)` from a `LaunchedEffect(messages.size)` firing only on growth.
27. **Counter telemetry is a separate channel from text-aware loggers.** Memory pipeline classes inject BOTH a `logger: (String) -> Unit` (counts/IDs/accelerator names — logcat-safe) AND a `TelemetryCounters` (off-device). DO NOT bridge logger → telemetry. `TelemetryPayloadBuilderTest` has a canary asserting a seeded marker never appears in any payload.
28. **Logcat tags for production loggers come from the DI module, not the class** (e.g. preflight lines tag `ClassifierModule`). Diagnostic filter: `adb logcat -s EagerWarmUp:I InferenceSessionManager:I TelemetryWorker:I ChatViewModel:I ClassifierModule:I VerticalSearch:I BraveApi:I MemoryRetriever:I MemoryExtractor:I MemoryBackupController:I AndroidRuntime:E`. `BraveApi` logs the outgoing `q` (+ chunked raw response for `/llm/context`) — diagnostic only. Per #27 `MemoryRetriever` logs only on the error path; silence is normal.

### Search verticals

Routing maps each `SearchSubtype` to one adapter via `VerticalSearchDispatcherFactory` (`adapters[subtype] ?: generalAdapter`). Endpoint per vertical differs (#37). Sources/defaults live in `search_defaults.json`; the Settings Add-source dialog uses `defaultKindFor`.

30. **No STOCKS subtype** — single-instrument quotes are part of FINANCE (one `site:` query answers without a ticker round-trip). `SearchSubtypeDetector` has no `STOCKS_PATTERN` (folded into `FINANCE_PATTERN`); no STOCKS `when` branches. See #33.
31. **SPORTS and FINANCE use the Brave `site:` path, NOT RSS** (RSS only carries recent headlines). `VerticalSearchDispatcherFactory` wires SPORTS → `BraveSiteFilterAdapter(subtype=…)`, FINANCE → `FinanceQuoteAdapter` (+ `BraveSiteFilterAdapter` fallback, #33). `FeedAdapter` (RSS) is live for **WEATHER only** (#32). **SPORTS & FINANCE single-source/single-chip:** one domain per country, `maxDomains=1, maxCitations=1`; `BraveSiteFilterAdapter` trims only the post-cache `payload.sources` (UI chips) via `SearchPostProcessor.limitCitations`. **`payload.json` (model's `[SEARCH CONTEXT]`) keeps Brave's full top-N** — capping starves the model. A `BRAVE_SITE_FILTER` source with zero domains issues an *unfiltered full-web* search (returns Success). **NEWS** is the exception → `NewsKindRoutingAdapter`, fanning the NEWS site list by kind (RSS/DWML/HTML/JSON → `FeedAdapter(subtype=NEWS)`, `BRAVE_SITE_FILTER` → `BraveSiteFilterAdapter`) and merging. **Anti-pollution:** the Brave side runs ONLY when ≥1 `BRAVE_SITE_FILTER` source exists (else unfiltered-Success contaminates a feed-only list). When both run, chips concat/dedup/cap at 10, payloads wrap `{"subtype":"news","query":…,"feeds":…,"web":…}`. NEWS caps citations at 10, ships two US defaults (`apnews.com` + `reuters.com`).
32. **WEATHER renders deterministically and is NEVER sent to the LLM** (Gemma mangles numbers, can't read a feed, doesn't know location). Fetches a national source — Environment Canada RSS (CA) or `forecast.weather.gov` DWML (US, `SourceKind.DWML` + `DwmlParser`) — and `WeatherResponseFormatter` builds the bubble. **Location resolved at query time, not onboarding.** Onboarding captures **country only** (`OnboardingViewModel.saveLocation(country)`, seeds defaults). Per-query order in `AgentLoop`: (a) city+region from the query via `WeatherLocationResolver` against `LocationCatalog`; else (b) a saved location memory; else (c) a deterministic prompt for city + region. Resolved city's country selects the source via `DefaultSiteResolver`. **WEATHER force-fires outside the classifier:** when the query names a resolvable catalog city OR matches the tight whole-message `BARE_WEATHER_PATTERN`. Saved-location fallback (b) is reachable ONLY via the bare pattern. When the city came from the user's words, `AgentLoop` emits `Done.locationToRemember` → consent card (`MemoryExtractor.proposeLocationMemory`, deduped). `locations.json` covers CA + 50 US states + DC with GPS coords; new cities work automatically.
33. **Single-instrument FINANCE quotes render as a deterministic card via stockanalysis.com, bypassing the LLM.** `FinanceQuoteAdapter(fallback = BraveSiteFilterAdapter)`: (1) Brave `site:finance.yahoo.com` resolves the ticker from the URL's `/quote/<TICKER>/`; (2) GET `stockanalysis.com/stocks/<ticker>/` → `StockAnalysisParser` reads the minified `quote:{p,c,cp,h,l,v,h52,l52,…}` + `marketCap`/`peRatio`; (3) `StockResponseFormatter` renders a `"subtype":"stock_quote"` card. On ANY miss returns the Brave snippet unchanged → LLM fallback. **NOT cached** (price goes stale). `AgentLoop` has a FINANCE direct-render block after WEATHER. Needs `/web/search`, not `/llm/context` (#37).
35. **SPORTS runs Brave's LLM Context endpoint (`/res/v1/llm/context`), not `/web/search`** — pre-extracted page content (prose/tables) instead of ≤200-char snippets. `BraveSiteFilterAdapter` pinned to one domain but points at a second `SearchService` (`@SportsSearch`, `KtorBraveLlmContextClient`); `grounding.generic[]` → `LlmContextPostProcessor` → same `FormattedSearchPayload`. **Load-bearing tuning** (Gemma E2B mis-transcribes digits, "114"→"1114"): (a) `maxUrls=1`; (b) `LlmContextPostProcessor` drops chunks opening with `{`/`[` (structured JSON), keeps prose; (c) search-grounded turns decode greedy + drop history (#36). A deterministic score-card route is the only guarantee of correct digits — deferred (brittle for unstructured prose). `cacheNamespace="sports:"` so a `/web/search` payload can't serve a SPORTS turn.
38. **Relative-temporal queries force-fire search inside `PreflightRouter`** (NOT `AgentLoop` like WEATHER #32 — temporal force-fire WANTS the rewriter + subtype detection). Classifier under-fires on now-relative queries. `RelativeTemporalDetector` (`search/`) matches PAST/PRESENT/FUTURE relative phrases (topic-agnostic), widening the gate to `pSearch > highBand || temporalDetector.matches(query)`. `QueryRewriter.dateTimeRules` resolves `last/this/next` + `tomorrow` to concrete dates; unresolved phrases ("recently") pass through. **Excludes absolute dates** ("in 2019") and bare "now". Tracked via `preflight_temporal_force_total`. Logcat: ` forced=temporal`.
43. **An explicit "web search …" command at the START of a query force-fires search inside `PreflightRouter`** (like #38) — the user's deterministic escape hatch. `ExplicitSearchDetector` (`search/`) matches an **anchored leading** command — `web search …`, `search the web/online (for) …` (web-only; NOT `google`/`look up`). Anchored-at-start guards false positives ("how do web search engines work" doesn't fire). Widens the gate with `forceExplicit`. **Command words are stripped** (`stripPrefix`) before classifier/rewriter/subtype, but **vertical routing is kept**. **Fires even when the rewriter aborts** (the user asked). `SearchDisabled` still runs first. Tracked via `preflight_explicit_search_force_total` (explicit wins over temporal). Logcat: ` forced=explicit`. Default-constructed in `PreflightRouter`.

### Prompt assembly

34. **The `[SEARCH CONTEXT]` block + `PREFLIGHT_NOTICE` ride on the CURRENT user turn, NOT the system instruction.** `PromptAssembler.appendSearchContext` appends to the tail `HistoryMessage`; `buildSystemInstruction` no longer takes `searchContext`. **Don't move them back into the system prompt** — that reintroduces the disable-then-enable-search bug (a 2B model anchors on its own recent refusal adjacent to the generation point and ignores far-front evidence). Canonical RAG placement (most-recent) makes fresh results win. Defensive: if the tail isn't a `USER` turn, `appendSearchContext` leaves history unchanged. (Prior history is dropped on search turns anyway, #36.)
36. **Search-grounded turns drop prior history AND decode near-greedy.** Both gate on a non-blank `[SEARCH CONTEXT]` (GENERAL/NEWS/SPORTS; WEATHER/FINANCE render deterministically, never reach the LLM). (a) **History scoping:** `assembleStructured` calls `scopeToCurrentTurn`, keeping only the trailing USER turn for THIS generation; `AgentLoop` still persists full history. (b) **Greedy:** `AgentLoop` sets `GenerationRequest.sampling = SamplingParams.GREEDY` (`topK=1`, `temperature=1.0` — **NOT 0.0**, which risks divide-by-zero / stochastic-fallback; `topK=1` is the real lever). Reduces but does NOT eliminate digit errors (#35). Log: `historyTurns=N sampling=greedy(topK=1,temp=1.0)|default`.
37. **GENERAL/NEWS/SPORTS run `/llm/context` (each its own URL budget + cache namespace via a per-vertical `SearchService`); FINANCE deliberately stays on `/web/search`.** `KtorBraveLlmContextClient` ctor `maxUrls`/`maxTokens`/`maxSnippetsPerUrl` (default 1/1800/6):
    - **GENERAL** → default `SearchService`, `maxUrls=3`, `"ctx:"` (unpinned, >1 URL helps).
    - **NEWS** → `@NewsSearch`, `maxUrls=10`, `"news:"`; `BraveSiteFilterAdapter` `maxCitations=10`, `maxDomains=3`. Wrapped in `NewsKindRoutingAdapter` (#31).
    - **SPORTS** → `@SportsSearch`, `maxUrls=1`, `"sports:"` (#35).
    - **FINANCE** → `@FinanceSearch`, `KtorBraveSearchClient` (`/web/search`), `"fin:"`. **Must NOT use `/llm/context`** — its URLs lack `/quote/`, so `FinanceQuoteAdapter.resolveTicker` (#33) silently fails. `KtorBraveSearchClient` + `SearchPostProcessor.format` are **live, not deprecated**.
    Distinct cache namespaces keep response shapes + URL budgets from mixing in the shared `SearchCacheDao` (`"ctx:"` also invalidates pre-existing un-namespaced rows). `VerticalSearchModule` injects the four qualified `SearchService`s. `BraveSiteFilterAdapter`/`LlmContextPostProcessor`/`limitCitations`/greedy+scoping are endpoint-agnostic.

### Vision / image input

39. **Image input is gated at engine init, sent on the current turn only, and bypasses preflight/search.** Vision needs `EngineConfig.visionBackend` + `maxNumImages` at init (NOT per-request). `LiteRtInferenceEngine.newEngine` sets `visionBackend = Backend.GPU()` + `maxNumImages = 1` when `InferenceConfig.enableVision` (chat-load default flips it true; model keyed on `modelPath`, first loader's config wins). Image is a **downscaled JPEG `ByteArray`** on `…imageBytes` fields → `Message.user(Contents.of(Content.ImageBytes(bytes), Content.Text(text)))`. **`Content.ImageBytes`, NOT `Content.ImageFile`** (Photo Picker returns a `content://` Uri). **Model sees trailing-turn-only:** engine's history path is text-only; `PromptAssembler` strips `imageBytes` from non-trailing turns. **Display persists** the JPEG as a `messages.image_bytes` BLOB (migration `5.sqm`, v5→v6); `UserBubble` decodes on demand via `produceState`; cleanup free via FK `ON DELETE CASCADE`. `AgentLoop` still writes a text-only `ChatMessage.User` to in-memory history. **An image turn skips deterministic short-circuits AND preflight/search** (`hasImage` gate → `FallThrough(MiddleBand)`, `forceWeather` suppressed, warm sampling). UI: Android Photo Picker (`PickVisualMedia`, no permission) → `ImagePreprocessor` decodes + downscales longest edge to ~768 px (`ImageDecoder`, applies EXIF) + JPEG-encodes; no Coil. `StubInferenceEngine` echoes `[stub] received image of N bytes`. **Pending-attachment preview must match the sent bubble (PR #69):** the `ChatScreen` thumbnail uses `ContentScale.Fit` + `widthIn(max = 240.dp)` (NOT `Crop` at `size(56.dp)`); commonMain → both platforms. **Benign Pixel 7 noise:** native `litert: No dispatch library found / Failed to initialize Dispatch API` is LiteRT-LM probing for an absent NPU delegate (falls back to GPU, succeeds). Mute: `adb shell setprop log.tag.litert SILENT` or filter `litert:S`.
40. **LiteRT-LM and the classifier runtime ship colliding `libLiteRt.so` — pin BOTH to `litert:2.1.5`, force litert's copy to win via project-local jniLibs, never bump independently.** LiteRT-LM 0.11.0+ ships standalone `libLiteRt.so` + `libLiteRtClGlAccelerator.so` sharing names with `com.google.ai.edge.litert`'s libs; Android packages one `.so` per name and the builds are never byte-identical. **NOT interchangeable:** (a) LiteRT-LM's `libLiteRt.so` lacks `Java_com_google_ai_edge_litert_*` JNI symbols → classifier crashes `UnsatisfiedLinkError: Environment.nativeCreate`; (b) `litert:2.1.4`'s libs SIGSEGV `liblitertlm_jni.so` 0.12.0 in GPU init. **`litert:2.1.5` is the one combination serving both** (its `libLiteRt.so` is a superset that also drives `litertlm_jni`). **`pickFirst` alone does NOT reliably pick litert's copy** (merge order non-deterministic). **Fix:** the `extractLitertJni` Copy task in `androidApp/build.gradle.kts` pulls litert's two natives from its own AAR (resolved version, arm64-v8a) into a generated `jniLibs.srcDir` — project-local jniLibs always beat dependency libs in `pickFirst` — tracking the resolved version. Keep the `packaging { jniLibs { pickFirsts += ["**/libLiteRt.so", "**/libLiteRtClGlAccelerator.so"] } }` block. **Compile-only `assembleDebug` will NOT catch a regression here** — re-verify on-device, or check the merged `libLiteRt.so` exports `Environment_nativeCreate` (`nm -D`). `litertlm` + `aiEdgeLitert` versions are **coupled**.

### Markdown / LaTeX rendering

41. **Markdown + LaTeX render ONLY for answers the model composes freely; search-grounded and deterministic turns render plain — gated by a persisted `renderMarkdown` flag.** Finalized assistant text renders via Markwon + `io.noties.markwon:ext-latex` (jlatexmath — native canvas, offline, **NO WebView**) in an `AndroidView` TextView (`MarkdownMathText`); the streaming partial keeps plain Compose `Text` (per-token reparse would jank). `renderMarkdown` lives on `ChatMessage.Assistant`/`UiMessage.Assistant`, persisted as `messages.render_markdown` (migration `6.sqm` → v7), **false** for WEATHER/FINANCE cards, the clock/todo/weather-prompt/memory-ack handlers, AND every search-grounded LLM turn (signal: `searchContextBlock != null || citationsForTurn.isNotEmpty()`; markdown reflow mangles search results). **ext-latex 4.6.2 matches `$$…$$` only**; the model emits single-`$`, so `LatexNormalizer.normalize` rewrites `$…$`/`\(…\)`/`\[…\]` → `$$…$$` with a math-vs-currency heuristic. `MarkwonInlineParserPlugin` + `SoftBreakAddsNewLinePlugin` registered. TextView mirrors `bodyMedium`. All assistant turns share one `AssistantBubble`; `renderMarkdown` swaps only the inner content. **Dev-build identity:** `BuildConfig.GIT_DESCRIBE` shows in the About dialog (versionCode = HEAD's commit timestamp) — a stale install is obvious.

### Voice I/O — dictation (STT) + read-aloud (TTS)

42. **Voice I/O is entirely `:androidApp` (no `:shared` seam), behind `ChatSpeaker`/`SpeechDictation`.** Input row order: **mic · speaker · photo · Send**.
    - **Read-aloud fires ONLY at `AgentEvent.Done`, never on streaming tokens.** `ChatViewModel` speaks `MarkdownToPlainText.strip(message.text)` (citations excluded); `AndroidTtsSpeaker.speak` uses `QUEUE_FLUSH`.
    - **The "working on it" ack + 5 s "still working" heartbeat gate on `AgentEvent.GenerationStarted`, NOT `send()`** — `AgentLoop` emits it right before `session.generate`, so deterministic short-circuits (which return earlier) stay silent. **Don't move the ack into `send()`.** Heartbeat runs only GenerationStarted→Done. `AgentEvent` is a sealed interface — a new variant breaks the exhaustive `when` in `ChatViewModel.onAgentEvent`.
    - **Mic dictation is a continuous toggle, session-only.** `SpeechRecognizer` is single-shot, so `SpeechDictation` self-restarts on every result/timeout (restart on all errors except `ERROR_INSUFFICIENT_PERMISSIONS`, 150 ms debounce). Needs `RECORD_AUDIO` + a `<queries>` `RecognitionService` manifest entry (Android 11+). **Defaults OFF each launch, NOT persisted.** (Speaker toggle IS persisted via `TtsPreferences`.) No "microphone on" voice command.
    - **Dictation streams the live transcript into the prompt box (PR #67) — partials are display-only; commands + committed text key off finalized results.** `Dictation` exposes `partials: Flow<String>` (live, rewrites mid-utterance) alongside `results: Flow<String>` (finalized). Android sets `EXTRA_PARTIAL_RESULTS=true` + emits in `onPartialResults`; desktop Vosk emits `recognizer.partialResult`'s `"partial"` per frame (deduped). `ChatScreen` keeps a `committedInput` anchor + renders `committedInput (+ space) + partial`; a finalized `results` emission (NONE branch) **or a manual keystroke** promotes the partial into `committedInput`. **`VoiceCommand.match` runs ONLY on `results`** (whole utterances), never on a partial — the transient command partial flashing in the box is discarded on finalize. Echo suppression drops partials during TTS too. **Don't act on a partial** and **don't send `input`** for a voice SEND — send `committedInput`.
    - **Echo suppression uses a grace tail, not instantaneous `isSpeaking`.** During read-aloud the mic stays listening command-only (so "speaker off" can interrupt). Non-command text dropped while `suppressDictationText` = `ttsSpeaking` + a 2.5 s trailing grace. Best-effort; the button is the guaranteed stop.
    - **Voice commands match the WHOLE utterance** (`VoiceCommand.match`, case/punct/whitespace-insensitive): send/cancel/clear/new chat/microphone off/speaker off/speaker on. SEND reuses the Send button's guard; speaker on/off route through idempotent `setTtsEnabled`.
    - **TTS voice/rate/pitch is the OS setting on ANDROID — no in-app picker.** `AndroidTtsSpeaker` only sets `language = Locale.getDefault()`.
48. **Desktop read-aloud (PR #66) shells out per-OS (`say`/`spd-say --wait`/PowerShell `SpeechSynthesizer`) — stopping it is OS-specific.** `DesktopTtsSpeaker.stop()` calls `process.destroy()`, which silences macOS `say` + Windows (in-process) but **NOT Linux**: `spd-say` is only a *client*; the speech-dispatcher daemon keeps playing after the client dies, so `stop()` **also issues `spd-say --cancel`** on Linux. **Desktop voice IS configurable** (unlike Android): engine (Linux output module, `spd-say -o`) / voice (`-y` / `say -v` / `SelectVoice`) / rate live on the **concrete `DesktopTtsPreferences`** (`tts_prefs.json`), deliberately OFF the shared `TtsPreferences` interface (same split as desktop UI-zoom, #45). `DesktopTtsVoices` enumerates options (`spd-say -O`/`-L`, `say -v ?`, PowerShell `GetInstalledVoices`); the Settings picker is a desktop-only `expect/actual DesktopVoiceSection()` (Android actual = no-op). **Real quality win is the bundled Piper neural engine, not the OS engine.** `engine == DesktopVoiceConfig.PIPER_ENGINE` ("piper") routes `DesktopTtsSpeaker` to `PiperSpeechSynthesizer`. Piper is self-contained + offline: `PiperBinaryStore` downloads the prebuilt `rhasspy/piper` executable (pinned `PiperRelease.TAG`, per-OS/arch asset table w/ verified sha256, extract via system `tar` / Windows `ZipInputStream`, `$ORIGIN`-rpath bundles onnxruntime+espeak-ng-data) and `PiperVoiceStore` downloads the ONNX voice + `.onnx.json` (HF `rhasspy/piper-voices`, `en_US-lessac-medium` ≈ 63 MB) — both via the same `DesktopModelDownloader`/`DesktopModelInventory`/app-data layout as the LLM/Vosk models. Synth is `piper --model … --config … --output-raw` (raw S16LE mono @ model sample rate) streamed to a **`javax.sound.sampled.SourceDataLine`** — no `aplay`/Python. So Piper **stops instantly** (in-JVM line close + process kill). Rate maps to `--length_scale` (smaller = faster). The picker offers Piper only when `PiperRelease.assetForHost() != null`; selecting it triggers `prepare()` (pre-download w/ progress in `PiperState`). OS speech-dispatcher modules remain the non-neural fallback.

### Desktop appearance & window (CMP, PR #61)

45. **Desktop UI "zoom" scales `LocalDensity.density`, NOT `fontScale` — different levers.** `AppThemeScaffold` (shared `:ui`) takes `densityScale: Float = 1f` (default ⇒ Android untouched) and applies `Density(base.density * densityScale, base.fontScale * fontScale)`. Density scales `dp`+`sp` (whole UI: icons, padding, forms, text); `fontScale` is the text-only Settings slider. **For "everything too small" use density, never fontScale.** Desktop `Window(onKeyEvent=…)` drives it via **Ctrl/Cmd `+`/`-`/`0`** (modifier-gated so it never eats typed chars); persisted desktop-only on `DesktopThemePreferences.uiZoom*` (`theme_prefs.json`, `UiZoom` bounds 0.8–2.0) — OFF the shared `ThemePreferences` interface. `Main.kt` reads the **concrete** `DesktopThemePreferences` from Koin.
46. **Desktop theme is monochrome, not the Android green; `ThemeMode.System`/Auto is unreliable on Linux.** Desktop `Theme.kt` defines `LightMonochromeColorScheme` (white surfaces, black trim) + `DarkMonochromeColorScheme` (near-black `#121212`, white trim), with `surfaceTint = Color.Transparent` to kill M3 tonal-elevation overlays; `error` stays red. Android keeps Material You / the green fallback. **`isSystemInDarkTheme()` on desktop is Skiko-backed — works on macOS/Windows, but on Linux commonly reports light with no live update (JetBrains CMP-6028).** No Linux detector wired; use the manual Light/Dark selector there. Status dots (memory/connection green-yellow-red in `:ui`) are semantic, not theming — leave them.
47. **Desktop window geometry is persisted; chat bubbles size to window width.** `DesktopWindowPreferences` (`window_prefs.json`) saves size/position/maximized; `Main.kt` seeds `rememberWindowState` + saves on change via `snapshotFlow` + `collectLatest` debounce (width/height saved only while *floating* so maximizing doesn't clobber the restore size; position only when absolute). Chat bubbles: `ChatScreen.kt` wraps the list in `BoxWithConstraints` — `<600.dp` keeps the mobile 320 dp cap, wider uses `(maxWidth*0.72f).coerceIn(480.dp,760.dp)`; threaded as `maxWidth: Dp` into `UserBubble`/`AssistantBubble`/`StreamingAssistantBubble`.

### Jobs (PR #70)

Time-triggered work that runs **only on the desktop agent** (cron or one-shot), with a mobile remote view. **Full as-built design + the rationale for all four invariants below: `docs/JOBS_PLAN.md` §0.**

49. **A job is `command` + `prompt`, always a desktop subprocess; each run continues the job's ONE conversation thread.** `JobExecutor` runs `command` with `prompt` as a bound positional shell arg (injection-safe), appends `user`/`assistant` (`renderMarkdown=false`) to the job's `last_run_conversation_id`. `JobScheduler`/`JobExecutor`/`JobService` (commonMain `JobAdmin` seam; **null on mobile → read-only UI**) + `cron-utils` are **desktopMain-only**. See JOBS_PLAN §0.
50. **Job trust boundary = the injected `JobSyncPolicy`, fail-closed; the `*FromPeer` write needs the `onJobPausedFromPeer` seam to re-drive the scheduler.** Create/edit/delete/run-now are desktop-only; a mobile peer may only toggle `paused`. `DesktopJobSyncPolicy` drops remote inserts + tombstones; the raw `updatePausedFromPeer` query fires no `LocalChangeBus`, so `applyFromPeer` must call `onJobPausedFromPeer` → `JobService.reactToPausedChange`. `jobs` syncs LWW+tombstone with denormalized last-run; `job_runs` is desktop-local, NOT synced. See JOBS_PLAN §0.
51. **`SqlDelightJobRepository.flow()` MUST be a reactive SQLDelight query (`selectAllJobs().asFlow().mapToList`), NOT a seeded `StateFlow`.** Synced rows are written via raw `*FromPeer` queries that bypass the repo, so the tasks/todos `MutableStateFlow` pattern goes stale (a synced job is invisible until restart). Local writes still call `LocalChangeBus.notifyChanged()`. See JOBS_PLAN §0.
52. **No Material3 `DatePicker` on desktop — its kotlinx-datetime calendar model throws `NoSuchMethodError` (`KotlinxDatetimeCalendarModel.getToday`).** The Jobs schedule form reuses the mobile clock UI (**Repeat**=alarm flow → 5-field cron, **Once**=timer flow → one-shot `fireAt`); desktop swaps the analog clock for `TimeInput`. The monochrome theme also had to define the M3 `surfaceContainer*` roles or `AlertDialog`/menus tint purple. Mobile `RuleSettingsIcon` is a hand-built `ImageVector`. See JOBS_PLAN §0.

### Desktop deployment (PR #72)

53. **`MOBILEAGENT_HEADLESS=1` runs the desktop agent as a startup service without opening the main window.** The full background runtime launches on `appScope` **before** the `application{}` block (headless only changes *window startup*): tray available → `windowVisible=false`; no tray → fully windowless on a `CountDownLatch` + SIGTERM/Ctrl-C hook mirroring `shutdown()` (no `exitProcess`). `AppIcon` is `lazy` so the windowless path touches no AWT/Skia. Single-instance file lock (`<app-data>/.instance.lock`) shared across modes. **Full detail + service templates (`desktopApp/packaging/`) + deploy guide: `docs/DESKTOP_PACKAGING.md` "Headless / standalone deployment".**

## Build & run

```bash
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
./gradlew :androidApp:installDebug -PuseStubEngine=true     # dev without real Gemma (StubInferenceEngine)
./gradlew :androidApp:installDebug -PexternalModels=true    # strip 88 MB classifier+embedder from APK (see below)

# Launch / M0 spike harness
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity
```

**Skip the Gemma download during dev** (prod read path: `filesDir/models/gemma-4-E2B-it.litertlm`, written by `ModelDownloadWorker`):

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as com.contextsolutions.mobileagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'
```

**Skip bundling classifier + embedder (`-PexternalModels`):** push the two aux models to `filesDir/models/` once; the engines (`LiteRtClassifierEngine`/`LiteRtEmbedderEngine`) prefer filesDir, else the bundled asset. Release builds always bundle them (don't set the flag). Copy ONE file per `run-as cp` (multi-file `cp` mis-parses through the nested shell quoting):

```bash
PKG=com.contextsolutions.mobileagent.debug
for f in preflight_memory_shared_v1.0.0_int8.tflite all-MiniLM-L6-v2_int8.tflite; do
  adb push "models/$f" /data/local/tmp/
  adb shell run-as $PKG cp /data/local/tmp/$f /data/data/$PKG/files/models/$f
  adb shell rm -f /data/local/tmp/$f
done
```

If the models are absent on an `-PexternalModels` install, warm-up fails (`ClassifierEngine: classifier warmUp failed`) and the agent silently falls through to Gemma. On success: `loading classifier from filesDir (…)` then `classifier loaded on CPU` (the `GPU init failed; falling back to CPU XNNPACK` line is normal — #18's GPU refusal).

**Wireless adb** (Pixel 7's USB is unstable — pair once, re-`connect` after each reboot; port changes, pairing persists):

```bash
adb pair <phone-ip>:<pairing-port>     # one-time, 6-digit code
adb connect <phone-ip>:<connect-port>  # after reboot or WiFi drop
```

**Classifier training pipeline (M3/M4/WS-14):**

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"        # gen, review, dedup, stats CLIs
pip install -e ".[training]"   # adds torch/transformers/litert-torch (CUDA)
```

Reproduce v1.0 (~10 min on RTX 5090): `ct-train-classifier` → `ct-eval-classifier` → `ct-export-litert`. Gen is Ollama-backed (`qwen3.5:9b`); `CT_GEN_BACKEND=claude` + `ANTHROPIC_API_KEY` for the Claude path. Full CLI list in `classifier-training/pyproject.toml`.

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

**Secrets:** `secrets.properties` lives at `android-app/secrets.properties` (next to `settings.gradle.kts`), NOT the repo root. See `android-app/secrets.properties.example`.

## Working norms

- **Don't commit unless explicitly asked.** The user reviews diffs and decides.
- **Read the official integration guide BEFORE introspecting the JAR.** `https://ai.google.dev/edge/litert-lm/android` documents intended usage that can't be reverse-engineered from signatures. Then `javap -cp <jar> -p com.google.ai.edge.litertlm.X` for exact signatures.
- **Keep all LiteRT-LM types behind `LiteRtInferenceEngine`.** The `InferenceEngine` interface in `commonMain` is the seam.
- **Documentation hygiene:** when a Phase 1 decision changes, update both `PHASE1_PLAN.md` and `M0_DECISION_MEMO.md`.
- **Brief responses.** The user reads diffs and code; don't recap them in prose.
