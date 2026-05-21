# Context for Claude

This file is auto-loaded into every Claude Code session. Goal: don't make a
fresh-context Claude rediscover what M0–M6 already cost time to learn.

## Always read first

Before suggesting architecture, scope, or code structure, read:

1. `PRD.md` — product spec (locked, treat as ground truth)
2. `PHASE1_PLAN.md` — milestone plan + per-M status (kept up to date)
3. `SYSTEM_PROMPT.md` — system prompt construction spec
4. `docs/M0_DECISION_MEMO.md` — ratified hardware/runtime decisions

On demand (don't read cold; read when the work touches that milestone):
- `docs/M{3,4,5,6}_PLAN.md` — per-milestone phase logs + ratified decisions
- `docs/M{3,4,5,6}_*_HANDOFF.md` — operational handoff notes (deferred items, findings)
- `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` — classifier eval + known weaknesses
- `CLASSIFIER_DATASETS.md` — pre-flight + memory extractor dataset spec

## Project at a glance

On-device AI assistant for Android. **Pixel 7 + Android 16 only** for Phase 1.

- **LLM runtime:** LiteRT-LM 0.10.2 (`com.google.ai.edge.litertlm:litertlm-android`). Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, 2.58 GB). GPU via Play Services TFLite OpenCL on Mali-G710; CPU fallback when GPU init throws. E4B ruled out by LMKD thrash on 8 GB Pixel 7.
- **Classifier + embedder runtime:** `com.google.ai.edge.litert:litert:2.1.4` (a different runtime from LiteRT-LM; see invariant #18).
- **Classifier:** shared DistilBERT-base encoder + 3 task heads, INT8 → `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB), bundled in `:androidApp/src/main/assets/`. Three outputs per forward pass.
- **Embedder:** all-MiniLM-L6-v2 INT8 (23.5 MB), bundled. Mean-pool + L2-norm baked in; output is one 384-dim vector.
- **Architecture:** KMP shared module (`:shared`) + Android Compose shell (`:androidApp`). iOS targets stubbed for Phase 2.
- **Toolchain:** JDK 17, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.3.21, KSP 2.3.7, Hilt 2.59.2.
- **Status:** M0–M6 complete (see `PHASE1_PLAN.md` §5 for per-milestone summaries and test counts). M7 (closed beta → Play Store) not started.

## Hard invariants

Things prior milestones surfaced the hard way — don't rediscover. Numbering is stable; existing code/comments reference these by number.

### Inference runtime (LiteRT-LM, Gemma)

1. **Every LiteRT-LM call must run off the main thread.** `Engine.initialize()` blocks 4–8 s on Pixel 7 and will ANR. `LiteRtInferenceEngine` already wraps in `Dispatchers.IO` — keep it that way for any new caller.
2. **`GenerationRequest.maxTokens` is a no-op in LiteRT-LM 0.10.2.** No per-call cap surfaced. Cancel the Flow at the parser layer to stop early.
3. **`Backend.GPU()` requires `play-services-tflite-gpu:16.4.0` at runtime.** Without it, GPU init throws `Cannot find OpenCL library on this device`. Devices without recent Play Services fall back to CPU via `LiteRtInferenceEngine.tryInitialize`.
4. **Pixel CDEV throttling drops GPU clocks before `PowerManager.currentThermalStatus` reflects it.** If we need accurate throttling signal, infer from measured tok/s drift, not the high-level API.
8. **Tool calling MUST go through LiteRT-LM's structured channel.** Register via `ConversationConfig.tools` (using `tool(OpenApiTool)` to wrap a description JSON). Text-only schemas in the system prompt do NOT unlock tool-use mode — the model defaults to "I don't have real-time data" refusals.
9. **Reuse the same `Conversation` across the multi-step tool-call cycle.** Per https://ai.google.dev/edge/litert-lm/android: `sendMessageAsync(userText)` → if `Message.toolCalls` populated, execute tool → `sendMessageAsync(Message.tool(...))` on the **same conversation**. Re-creating per step and replaying via `initialMessages` does NOT preserve Gemma's call↔response correlation; the model just re-emits the call until the per-turn cap fires. `LiteRtInferenceEngine.generate` drives this loop internally and exposes a `ToolDispatcher` callback to the agent layer.
10. **`Content.ToolResponse(name, response)` needs a structured payload, not a raw JSON string.** Gson treats a `String` as a JSON-quoted string, so passing `"[{\"title\":...}]"` makes Gemma see a quoted blob instead of an array. The engine parses with kotlinx.serialization and hands a `List<Map<String, Any?>>` to `Content.ToolResponse`. See `LiteRtInferenceEngine.parseAsStructured`.
11. **Don't pre-template `<start_of_turn>...<end_of_turn>` markers in the prompt.** LiteRT-LM applies Gemma's chat template internally when you use `ConversationConfig` + `sendMessageAsync`. `PromptAssembler.assembleStructured` returns markers-free.
29. **`Conversation.cancelProcess()` is the only way to actually stop an in-flight decode.** `Job.cancel()` on the consuming coroutine closes the Flow chain but LiteRT-LM's native worker keeps decoding to end-of-turn, holding GPU/CPU and freezing the UI. `LiteRtInferenceEngine.bindCancellation` registers an `invokeOnCompletion` on the current `Job` that fires `conversation.cancelProcess()` when the parent cancels; the finally block calls it defensively before `conversation.close()`. **Don't rely on `Job.cancel()` alone to bound LiteRT-LM CPU/GPU time.** The same primitive exists on `com.google.ai.edge.litertlm.Session` for the lower-level API; not currently wired because production uses `Conversation`. Discovered via `javap` on the api.jar — not currently mentioned in the public LiteRT-LM Android docs. **This cancellation path is the ONLY lever that recovers a GPU-saturation freeze (the M7 main-thread stall), so the watchdog has to drive it, not just the user.** PR #31: `InferenceSessionManager` tracks the in-flight generation `Job`(s) and `forceUnload(UnloadReason.MainThreadWatchdog)` cancels them — which fires `cancelProcess()` via this path — so the deferred unload can actually fire. Before #31 a watchdog trip during a hung decode was a silent no-op: `forceUnload` only set `pendingForceUnload` and waited for the Flow to complete, which never happens while the decode is wedged. **`forceUnload` cancels in-flight generations ONLY for `MainThreadWatchdog`**; `LowMemory`/`TrimMemory`/`Manual` still defer (the app isn't hung in those cases, and cutting a live turn mid-stream is the worse UX). Caveat: if the GPU is wedged so hard that `cancelProcess()` itself can't return, this is best-effort — same limit as the manual Cancel button; the escalate-to-`Process.killProcess` last resort was considered and deferred.

`MarkerFunctionCallParser` is legacy (text-marker workaround pre-#8). With invariants 8–11 in place, tool calls surface structurally via `Message.toolCalls` — the parser is unused in production but still has tests.

### Classifier + embedder runtime (ai-edge-litert)

12. **The classifier .tflite has 2 inputs + 3 outputs identified by `tensor.name()`, NOT interpreter index.** The runtime permutes both input and output indices away from name order, silently.
    - **Inputs** (`int64`, `[1,128]`): `serving_default_args_0:0` → `input_ids`, `serving_default_args_1:0` → `attention_mask`. Dispatch by parsing the `_N:0` suffix from `getInputTensor(i).name()`.
    - **Outputs** (`float32`): `:0` → preflight `[1,3]` `[search_required, search_not_required, ambiguous]`; `:1` → presence `[1,2]` `[no_extraction, has_extraction]`; `:2` → category `[1,6]` multi-label sigmoid `[personal_identity, preference, professional, interest, relationship, temporary_context]`. Dispatch by parsing the `:N` suffix from `getOutputTensor(i).name()`. Hardcoding indices ships a silent swap.
13. **Classifier tokenizer must match training-time `distilbert-base-uncased` exactly.** Different vocab (cased vs uncased), different sub-word splits, or a stale `vocab.txt` silently degrades the classifier with no error. The Android side bundles the 30,522-entry WordPiece vocab and lower-cases input. `WordPieceTokenizerFixtureTest` asserts byte-exact input_ids vs the Python tokenizer on 22 fixtures — if you re-export the model, re-run this test.
14. **Pre-flight thresholds are CONFIGURABLE per PRD §3.2.1.** Shipped via `:androidApp/src/main/assets/preflight_config.json`, read at startup. Currently **0.4 high / 0.15 low** — relaxed from the original 0.85 (→ 0.5 → 0.4) after on-device testing showed the v1.0 classifier under-firing on weather/sports/news/finance queries the vertical adapters handle cleanly. The asset is the runtime source of truth; `PreflightThresholds.DEFAULT` (still **0.5f** in code) only fires if the asset fails to load, and `CanonicalEvalTest`'s middle-band fixture (softmax ≈ 0.452) is pinned to that default — bump them together if you change it. Don't hard-code — that blocks telemetry-driven tuning, which is the documented path to closing the v1.0 §7 precision gap.
15. **Classifier .tflite sequence length is statically baked at export (currently 128).** Different lengths require re-export via `ct-export-litert --max-length N`.
16. **`ai-edge-torch` was renamed to `litert-torch` in 2025.** Use `import litert_torch` for new code; the export driver falls back to `ai_edge_torch` only for older saved sessions. Quantization recipes in `litert_torch.generative.quantize` are LLM-specific (not for encoder classifiers) — use `ai_edge_quantizer.Quantizer` with `MIN_MAX_UNIFORM_QUANT` for encoder INT8 weight-only quant.
17. **`models/` is gitignored — model cards live in `docs/`.** Keep the model card at `docs/preflight_memory_shared_vX.Y.Z_MODEL_CARD.md` so it tracks in git alongside the handoff note.
18. **Pre-flight classifier MUST run on `com.google.ai.edge.litert:litert:2.x` — NOT `org.tensorflow:tensorflow-lite` and NOT Play Services TFLite.** Both classic TFLite runtimes produce numerically broken outputs for our `ai-edge-quantizer` INT8 model (logits ~1500× the Python reference magnitude, every query collapsing to one class). No exception, just silently wrong arithmetic. `com.google.ai.edge.litert:litert:2.1.4` is the Android port of Python's `ai-edge-litert` — the runtime ai-edge-quantizer's export tooling targets. Different package (`com.google.ai.edge.litert.CompiledModel`), different native libs (`libLiteRt.so`). LiteRT-LM (Gemma 4) keeps its own runtime via `play-services-tflite-*`; both coexist on the classpath. **Class-collision workaround:** the litert AAR bundles `org.tensorflow.lite.*` and so does the transitive `tensorflow-lite-api`; AGP rejects duplicates. Resolved via `configurations.matching { ... }.configureEach { exclude(group = "org.tensorflow", module = "tensorflow-lite-api") }` in `:shared/build.gradle.kts` and `:androidApp/build.gradle.kts`.

The embedder uses the same runtime (#18) and same vocab as the classifier — `assets/vocab.txt` is byte-identical SHA-256 to bert-base-uncased's, so both engines share one tokenizer singleton via Hilt.

GPU delegate refuses both the classifier and embedder graphs (`BROADCAST_TO`, `EMBEDDING_LOOKUP`, `CAST INT64→FLOAT32` unsupported). CPU XNNPACK is the only path. Classifier p95 = 113 ms; embedder p95 = 41 ms on Pixel 7.

### Build, Android platform, framework gotchas

5. **`:shared` uses `com.android.kotlin.multiplatform.library`**, not the old `com.android.library` + `kotlin.multiplatform` combo (AGP 9 forbids it). Android config goes inside `kotlin { android { } }`.
6. **WorkManager's `SystemForegroundService` needs `foregroundServiceType` merged in our manifest.** Any worker that calls `setForeground(...)` will crash the worker process with `IllegalArgumentException: foregroundServiceType 0x1 is not a subset of 0x0` unless we declare the type via `tools:node="merge"` in `AndroidManifest.xml`. Currently `dataSync` for the model download.
7. **`POST_NOTIFICATIONS` must be requested at runtime on Android 13+.** Manifest permission alone is not enough — the OS silently suppresses every notification (including FGS-required ones) until granted. `MainActivity.ensureNotificationPermission` does this on first launch.
19. **AGP 9 KMP source-set DSL doesn't accept the closure form for catalog `Provider` deps.** `implementation(libs.foo) { exclude(...) }` inside `kotlin { sourceSets { androidMain.dependencies { ... } } }` fails. Workaround: configuration-level `configurations.matching { it.name.startsWith("androidMain") || ... }.configureEach { exclude(...) }` outside the `kotlin {}` block.
20. **SQLDelight `.sqm` files go alongside `.sq` files in the same package directory** (NOT a `migrations/` subdir). Schema version is auto-derived: with `N.sqm` present, `Schema.version` becomes `N + 1`. `verifyMigrations = true` REQUIRES a committed `.db` schema snapshot per prior version in `schemaOutputDirectory` (`src/commonMain/sqldelight/databases/`) — without one, code-gen itself fails.
    **Snapshot dance for a new migration:** (a) generate `<currentVersion>.db` FIRST via `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema` while the `.sq` files are still at the old state; (b) write the new `.sqm`; (c) update the `.sq` schema; (d) rebuild. Because `ALTER TABLE ADD COLUMN` appends to the end, the `.sq` file MUST declare new columns at the END of `CREATE TABLE`, otherwise `verifyMigrations` flags column-order drift.
21. **Hilt `@Provides` methods do NOT auto-fill default constructor parameters.** If a class has `@Inject constructor(..., dep: Foo = NoOpFoo)` AND it's constructed by a `@Provides` factory, the factory MUST explicitly pass the real `Foo` — otherwise the default fires and the dep is silently no-opped in production. M6 Phase C hit this for `TelemetryCounters` across multiple modules.
22. **For "do X whenever screen Y is visible" use `LifecycleResumeEffect(key)`, NOT `LaunchedEffect(key)`.** `LaunchedEffect` re-fires only on key change and misses background→foreground (the route key is unchanged). `LifecycleResumeEffect` re-fires on key change OR `ON_RESUME`. Pair with `onPauseOrDispose { job?.cancel() }`. Load-bearing for the aux (classifier + embedder) eager warm-up on Chat re-entry. Gemma is NOT eagerly warmed any more (PR #25 reversed M6 Phase B) — it loads on the first `InferenceSessionManager.generate()` call.
23. **Firebase SDK deps live in `:androidApp`, NOT `:shared/androidMain`.** Define the abstraction (`AnalyticsSink`, `SafeCrashReporter`) in `:shared/commonMain`; implement in `:androidApp/.../telemetry/` or `:androidApp/.../observability/`.

### Privacy, telemetry, redaction (M6)

24. **Firebase Crashlytics has NO `beforeSend` egress hook** (unlike Sentry). Redaction lives at every callsite, behind the `SafeCrashReporter` facade. Direct `FirebaseCrashlytics.getInstance(...).recordException(...)` outside the facade is a contract violation. The facade's `recordException` wraps in a `RedactedThrowable` (preserves stack trace + class name, scrubs message). **Never put user text in exception messages or breadcrumbs in the first place** — the redactor is defense-in-depth.
25. **Crashlytics non-fatals batch until next app launch.** `sendUnsentReports()` forces immediate upload — exposed via `SafeCrashReporter.flushPending()` for the debug button. Crashlytics dedupes non-fatals by exception class + top-of-stack signature; a re-record bumps the existing issue's events count rather than creating a new entry.
26. **Compose `liveRegion = Polite` on growing/streaming text fires on every content update**, so TalkBack re-reads the whole growing string. Do NOT put `liveRegion` on the streaming bubble. For one-shot announcements use `LocalView.current.announceForAccessibility(text)` from a `LaunchedEffect(messages.size)` that fires only on growth. `announceForAccessibility` is deprecated in API 36 but still the canonical primitive for this case.
27. **Counter telemetry uses a separate channel from text-aware loggers.** Memory pipeline classes inject BOTH a `logger: (String) -> Unit` (counts, IDs, accelerator names — safe for logcat) AND a `TelemetryCounters` (off-device-bound). DO NOT bridge logger → telemetry. `TelemetryPayloadBuilderTest` includes a load-bearing canary test seeding a unique marker string into `memories` and asserting it never appears in any built payload.
28. **Logcat tags for production loggers come from the DI module, NOT the class.** Preflight log lines tag `ClassifierModule`, not `PreflightRouter` — the tag is set in `ClassifierModule.providePreflightRouter`. Production diagnostic filter: `adb logcat -s EagerWarmUp:I InferenceSessionManager:I TelemetryWorker:I ChatViewModel:I ClassifierModule:I MemoryRetriever:I MemoryExtractor:I MemoryEvictor:I AndroidRuntime:E`. **By invariant #27, `MemoryRetriever` only logs on the error path** — successful retrieval is counters-only. Silence is normal; logcat entries mean a failure branch fired.

### Search verticals (PR #23 routing; PR #27 STOCKS; PR #34 SPORTS→Brave)

30. **The STOCKS vertical resolves company → ticker over the network via stockanalysis.com — NOT a bundled table and NOT the LLM** (a 2B Gemma confidently hallucinates wrong tickers → wrong company's data). `SearchSubtypeDetector` routes single-instrument queries ("Nvidia stock price", `$NVDA`, "market cap of X") to `SearchSubtype.STOCKS`, **checked before FINANCE** (which keeps market-news / macro / crypto / forex). `StockLookupAdapter` strips price/question stopwords to isolate the entity, then `GET https://stockanalysis.com/api/search?q=<entity>` → `{data:[{s:symbol, t:type, n:name}]}` where `t=="s"` is a stock and `"e"` an ETF; it takes the first `t=="s"` hit (else the first of any type), builds `…/stocks/<sym>/` (or `…/etf/<sym>/`), fetches it, and extracts text via `HtmlReadabilityExtractor`. Misses (no entity, empty `data`, blank page, network error) return `SearchOutcome.Error`, which `AgentLoop` surfaces as context the LLM explains — no crash. **Fixed endpoint ⇒ STOCKS has NO user-editable source list:** `VerticalPreferences.sitesFor`/`withSites` and `SearchSourcesScreen.defaultKindFor` carry no-op STOCKS branches purely to keep their `when`s exhaustive, and the Settings → Search sources screen omits a STOCKS section by design. **Known limitation (verify on device):** `HtmlReadabilityExtractor` only scrapes `<p>`/`<li>` runs, but stockanalysis.com renders price / market-cap / P-E in tables/divs — the summary reliably gets the company description but may miss the live numerics. If that bites, parse the page's embedded data rather than leaning on readability.
31. **SPORTS uses the Brave `site:` path, NOT RSS (PR #34).** `VerticalSearchDispatcherFactory` wires `SearchSubtype.SPORTS` to `BraveSiteFilterAdapter(subtype = SPORTS)` — the same adapter NEWS uses — reversing its original M-era `FeedAdapter` (RSS) wiring. RSS feeds only carry recent headlines and can't answer historical/specific queries ("who won the masters last year"); a web search restricted to `site:espn.com` can. `FeedAdapter` is still live for WEATHER and FINANCE. Consequence: sports sources in `search_defaults.json` and the Settings Add-source dialog (`defaultKindFor(SPORTS)`) are `BRAVE_SITE_FILTER`, with `endpointTemplate` holding the bare domain or a path-scoped filter (e.g. `bbc.com/sport`). The dispatcher runs exactly one adapter per subtype (`adapters[subtype] ?: generalAdapter`), so a SPORTS query no longer touches RSS at all; any leftover RSS-kind sports entry a user previously saved is silently ignored by `BraveSiteFilterAdapter`'s `BRAVE_SITE_FILTER` filter (falls back to an unfiltered Brave query).

## Build & run

```bash
# Build + install on connected Pixel 7
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug

# Dev iteration without loading the real Gemma model (StubInferenceEngine)
./gradlew :androidApp:installDebug -PuseStubEngine=true

# Launch / M0 spike harness
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity
```

### Skip the Gemma download during dev iteration

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as com.contextsolutions.mobileagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'
```

Production read path: `filesDir/models/gemma-4-E2B-it.litertlm` (internal storage, written by `ModelDownloadWorker`).

### Wireless adb (Pixel 7's USB has been unstable)

Pair once, then re-`connect` after each phone reboot (the connection port changes; the pairing persists). Phone: Settings → System → Developer options → Wireless debugging.

```bash
adb pair <phone-ip>:<pairing-port>     # one-time, takes the 6-digit code
adb connect <phone-ip>:<connect-port>  # after reboot or WiFi drop
```

### Classifier training pipeline (M3 / M4 / WS-14)

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"           # gen, review, dedup, stats CLIs
pip install -e ".[training]"      # adds torch/transformers/litert-torch (CUDA)
```

Reproduce the v1.0 classifier (~10 min on RTX 5090): `ct-train-classifier` → `ct-eval-classifier` → `ct-export-litert`. Generation is Ollama-backed by default (`qwen3.5:9b`); set `CT_GEN_BACKEND=claude` + `ANTHROPIC_API_KEY` for the Claude path. Full CLI list in `classifier-training/pyproject.toml`.

WS-14 regression gate (run before any new `.tflite` lands in `models/`):

```bash
ct-regression-check --ckpt ../eval/runs/<ts>/best.pt
ct-regression-check --skip-eval --ckpt path/to/metrics.json   # hosted-CI flow
```

Exit codes: 0 PASS / 1 SHA-mismatch / 2 regression / 3 infra error.

### Instrumentation tests (Pixel 7)

```bash
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.classifier.ClassifierLatencyBenchmark
```

Other useful classes: `ClassifierEndToEndTest`, `EmbedderEndToEndTest`, `MemoryRetrievalLatencyBenchmark`.

### Secrets

`secrets.properties` lives at `android-app/secrets.properties` (next to `settings.gradle.kts`), NOT the repo root. Gradle's `rootProject` is `android-app/`, so `rootProject.file(...)` resolves there; `mobile-agent/secrets.properties` is silently ignored. See `android-app/secrets.properties.example`.

## Working norms

- **Don't commit unless explicitly asked.** The user reviews diffs and decides.
- **Read the official integration guide BEFORE introspecting the JAR.** `https://ai.google.dev/edge/litert-lm/android` documents intended usage (tool registration, multi-turn conversations) that can't be reverse-engineered from API signatures alone. In M2 we burned several iterations replaying conversation history through `initialMessages` because we hadn't read the "reuse the same Conversation" guidance. Read the docs first, then `javap -cp <jar> -p com.google.ai.edge.litertlm.X` to pin down exact signatures.
- **Keep all LiteRT-LM types behind `LiteRtInferenceEngine`.** The `InferenceEngine` interface in `commonMain` is the seam; don't import `com.google.ai.edge.litertlm.*` anywhere else.
- **Documentation hygiene:** when a Phase 1 decision changes, update both `PHASE1_PLAN.md` and `M0_DECISION_MEMO.md`. Don't rely on commit messages alone.
- **Brief responses.** The user reads diffs and code; don't recap them in prose.
