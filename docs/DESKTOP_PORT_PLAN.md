# Desktop port of Mobile Agent — Compose Multiplatform + llama.cpp (v0.1.0)

## Context

The product today is a KMP Android app (`:shared` + `:androidApp`) — an on-device Gemma assistant. We want a **standalone desktop app for Linux / macOS / Windows** that reuses the existing code wherever possible, runs Gemma using the host GPU (NVIDIA CUDA / Apple Metal / Vulkan, CPU fallback), and can run **long-running tasks in the background surfaced through the OS system tray**.

Exploration confirmed the architecture is well-suited to this: **~89% of `:shared` (~14k LOC) is pure `commonMain`** behind clean interfaces — `AgentLoop`, all search verticals, the memory pipeline, the WordPiece tokenizer, prompt assembly, and the SQLDelight schema/DAOs reuse **as-is**. The desktop work concentrates in three places: (1) a new inference backend (LiteRT-LM is Android-only), (2) the UI/app shell (Compose + Hilt + Android lifecycle), and (3) ~790 LOC of platform seams that already have `commonMain` interfaces.

### Locked decisions (from the user)
- **LLM runtime:** llama.cpp via JNI (`de.kherud:llama`), in-process, GGUF Gemma, CUDA/Metal/Vulkan/CPU auto-select.
- **UI:** Compose Multiplatform — migrate Android screens into a shared `:ui` module, with thin Android + desktop shells.
- **Feature scope (all in):** core chat/search/memory, Voice I/O (STT+TTS), image/vision input, clock/alarms + todos, telemetry/crash reporting.
- **Tray + tasks:** BOTH — minimize-to-tray with a warm model + background in-flight generation, AND a new **queued agent-task system**.
- **Delivery:** new feature branch `feature/desktop-cmp`; released as tag **v0.1.0**.

### Cardinal rule
`:androidApp` must keep assembling and passing tests at **every commit**. Every step below is additive (new modules, source sets, actuals) until the final UI cutover, which is done **screen-by-screen**.

---

## Target module layout

```
:shared          KMP lib — commonMain + androidMain + iosMain + jvmMain("desktop")  (jvm target NEW)
:ui              KMP Compose Multiplatform UI — commonMain screens + androidMain + jvmMain  (NEW)
:androidApp      thin Android shell (Activity, Application, Android actuals/services)
:desktopApp      thin Compose Desktop shell (main(), window, tray, jpackage)  (NEW)
:desktopHarness  headless integration harness (Phase 0, optional keep)  (NEW)
```

- **iOS** stays declared in `:shared` (untouched) so expect/actual contracts keep compiling; **not** added to `:ui` for v0.1.0.
- Name the JVM target `jvm("desktop")` to disambiguate from the Android JVM bytecode target.

---

## Phase 0 — Branch + headless vertical slice (de-risk FIRST)

Prove llama.cpp + GGUF Gemma drives the real `AgentLoop` end-to-end with **no UI, no DI migration**. Retires the two highest-uncertainty risks before the expensive refactors.

1. `git checkout -b feature/desktop-cmp` off `main`.
2. Add `jvm("desktop")` + a minimal `desktopMain` to `:shared` (just enough to compile a harness).
3. New `:desktopHarness` (plain Kotlin/JVM app) that hand-wires `AgentLoop` — mirror `androidApp/.../app/di/AgentModule.kt` (`AgentLoopFactory`):
   - real `LlamaCppInferenceEngine` (Phase 4) against a downloaded GGUF Gemma;
   - `NoOpClassifierEngine` / `NoOpEmbedderEngine` returning null/empty (the interfaces support graceful degradation — `ClassifierEngine.warmUp` may return null);
   - Ktor CIO `HttpEngineFactory`, Brave key from env var.
4. Validate: cold-load latency, token streaming, **cancellation aborts native decode**, and deterministic clock/todo short-circuits fire with no model tool channel.

**Exit:** a desktop JVM process answers a chat turn through the real `AgentLoop` + llama.cpp.

---

## Phase 1 — Module restructure + `jvmMain` target

**`shared/build.gradle.kts`** — add `jvm("desktop")` inside `kotlin {}`; add `desktopMain` deps:
- `ktor-client-cio` (replaces OkHttp engine), `sqldelight-sqlite-driver` (JDBC, already in catalog at `libs.versions.toml:114`), llama.cpp JNI binding, ONNX Runtime (`onnxruntime` + optional `onnxruntime_gpu`), `kotlinx-coroutines-swing`.
- The existing `exclude(tensorflow-lite-api)` block (`shared/build.gradle.kts:108-113`) is androidMain-only — unaffected.

**`gradle/libs.versions.toml`** — add: Compose Multiplatform plugin (`org.jetbrains.compose`), Koin (`koin-core`/`koin-compose`/`koin-android`), `llama-jni`, `onnxruntime(-gpu)`, `ktor-client-cio`, STT lib (Vosk), a multiplatform markdown lib (`com.mikepenz:multiplatform-markdown-renderer`), `sentry`.

**`settings.gradle.kts`** — `include(":ui", ":desktopApp", ":desktopHarness")`; register the Compose MP plugin in `pluginManagement`.

At phase end `:ui`/`:desktopApp` are empty shells; `:androidApp` builds unchanged (nothing moved yet).

---

## Phase 2 — DI migration: Hilt → Koin (the big one)

Hilt is Android-only and CMP `commonMain` can't use it. Migrate the 18 modules under `androidApp/.../app/di/` to **Koin**, graph defined in `commonMain`, platform actuals in `androidMain`/`desktopMain`. Koin over manual factories: multiplatform DSL, no codegen, `androidContext()` for Context-needing actuals, `koin-compose` for ViewModels.

**Migration path (Hilt + Koin coexist during transition — Android stays green):**
1. Stand up Koin `module {}` in `commonMain` for everything pure-common (`PromptAssembler`, `SearchService`, vertical adapters, `MemoryRetriever`/`MemoryExtractor`, `PreflightRouter`, `QueryRewriter`, formatters, `AgentLoopFactory`) — mirror existing Hilt module bodies.
2. Platform Koin modules (`androidMain`/`desktopMain`) provide actuals: `InferenceEngine`, `ClassifierEngine`, `EmbedderEngine`, `HttpEngineFactory`, `SecureStorage`, `SqlDriver`/`MobileAgentDatabase`, all preferences repos, `AlarmScheduler`, `AgentClock`, `LocaleProvider`, headroom/thermal providers, telemetry sinks.
3. **Bridge on Android:** keep `@HiltAndroidApp`; `startKoin{}` in `onCreate`; replace each Hilt `@Provides` body with `getKoin().get()` so `@HiltViewModel`/`@Inject` keep resolving. Flip subsystems one at a time, full `:androidApp` test run after each (Search → Memory → Agent → …).
4. Cut ViewModels off Hilt (Phase 3).
5. Delete Hilt last: remove `libs.plugins.hilt`, `hilt-*`, Hilt-only `ksp` from `androidApp/build.gradle.kts:401-404` + catalog; drop `@HiltAndroidApp`/`@AndroidEntryPoint`/`@HiltViewModel`.

The 18 modules collapse to: common Koin set (`:shared` commonMain) + `androidModule` + `desktopModule`.

---

## Phase 3 — ViewModels + navigation (multiplatform-safe)

**ViewModels** (`ChatViewModel`, `OnboardingViewModel`, …): adopt the **multiplatform `lifecycle-viewmodel-compose`** artifact (CMP-supported in commonMain) — keep `viewModelScope`, drop `@HiltViewModel` for Koin `viewModelOf(::ChatViewModel)`. Strip Android types so they move to `:ui` commonMain:
- `android.util.Log` → common logger lambda (the pattern `AgentLoop` already uses).
- `Context`/`Uri`/`ImageDecoder` → behind an `ImagePreprocessor`/`ImageProvider` interface (Phase 7).
- Thin ViewModels (`TodoViewModel`, `ClockViewModel`, `MemoryViewModel`) move with minimal change.

**Navigation:** keep the manual sealed-route `when` from `MainScreen.kt` (`MainRoute.*` + `rememberSaveable`); move it to `:ui` commonMain. Replace `BackHandler` with `expect fun PlatformBackHandler(enabled, onBack)` — Android wraps `androidx.activity.compose.BackHandler`; desktop maps to Escape/window handler (or no-op since back buttons are explicit). Don't introduce Navigation-Compose for v0.1.0.

---

## Phase 4 — `LlamaCppInferenceEngine : InferenceEngine` (desktopMain)

New `shared/src/desktopMain/.../inference/LlamaCppInferenceEngine.kt` implementing the existing contract (`InferenceEngine.kt`) verbatim — `AgentLoop` stays backend-agnostic.

- **Param mapping:** `InferenceConfig.kvCacheTokens`→`n_ctx`; temp/topK/topP→sampler; `SamplingParams.GREEDY`→`top_k=1` (preserve RAG parity); `maxTokens`→`n_predict`; `stopSequences`→stop strings.
- **Chat template:** llama.cpp applies Gemma's template (built-in) — build the `<start_of_turn>` framing from `HistoryMessage`/`systemInstruction`, mirroring the structured-vs-raw branch the contract documents.
- **Streaming:** `flow{}`/`callbackFlow{}` pumping detokenized pieces → `GenerationEvent.TokenChunk`, then exactly one `Done`/`Error`; `.flowOn(Dispatchers.IO)`.
- **Cancellation:** `currentCoroutineContext().ensureActive()` between tokens + the binding's abort flag; run on a dedicated single-thread context (llama ctx not thread-safe). This replaces Android's `cancelProcess()`.
- **Backend select:** probe CUDA (NVIDIA driver) → set `n_gpu_layers`; macOS→Metal; else Vulkan; else CPU. Report via `ModelHandle.activeAccelerator` (map onto existing `Accelerator` enum; record specifics in telemetry, don't extend the enum).
- **GGUF acquisition (GGUF ≠ .litertlm):** new desktop `MODEL_DOWNLOAD_URL`+SHA+size for a GGUF Gemma (e.g. `Q4_K_M`). Reuse the near-portable `androidApp/.../app/service/ModelDownloader.kt` (depends only on `File`/`RandomAccessFile`/`MessageDigest`/coroutines/HTTP) — swap `android.os.StatFs`→`File.getUsableSpace()`, `Log`→common logger, OkHttp→desktop client; lift into common/desktop.
- **Tool channel:** low priority — `AgentLoop` dispatches clock/todo/memory/search deterministically before the model. Optionally emit `GenerationEvent.FunctionCall` via the dormant `FunctionCallParser`; otherwise no-op.

---

## Phase 5 — Desktop classifier + embedder (ONNX Runtime)

`.tflite` + ai-edge-litert has no desktop runtime. **Re-export to ONNX, run via ONNX Runtime Java** (CPU + optional CUDA EP).

- **Export pipeline** in `classifier-training/`: add `ct-export-onnx` (DistilBERT 3-head) + `export_minilm_onnx.py`, paralleling the existing `ct-export-litert`/`export_minilm_litert.py`. **Keep identical output tensor names** so head-resolution-by-name (invariant #12, `ClassifierEngine.kt:14-17`) carries over; fix seq length **128**, int64 `input_ids`/`attention_mask` (invariant #15).
- **`OnnxClassifierEngine : ClassifierEngine`** + **`OnnxEmbedderEngine : EmbedderEngine`** (desktopMain): `warmUp()` creates an `OrtSession` (try CUDA EP, fall back CPU, return GPU/CPU; failure→null per non-throwing contract); resolve heads **by tensor name, not index**.
- **Reuse `WordPieceTokenizer` + `vocab.txt` as-is** (only `unicodeNormalizeNfd` is expect/actual → JVM `Normalizer`).
- **Validate numerics** against existing Python reference fixtures (`classifier-training/tests/fixtures/embedder_canonical_outputs.json`).

---

## Phase 6 — Platform seams for `desktopMain`

| Seam | Desktop impl |
|---|---|
| `AgentClock` (`platform/Clock.kt` expect) | `Clock.desktop.kt` — java.time (trivial) |
| `LocaleProvider` (`platform/Locale.kt` expect) | `Locale.desktop.kt` — `java.util.Locale`/`ZoneId` |
| `unicodeNormalizeNfd` (tokenizer expect) | `.desktop.kt` — `java.text.Normalizer` NFD |
| `HttpEngineFactory` | Ktor **CIO** engine; reuse common `ContentRedactor` for logging |
| `SecureStorage` | **OS keyring** (`java-keyring`) preferred; **Java KeyStore (PKCS12)** fallback. Same `put/get/remove/contains` + markers (BRAVE_API_KEY, HF_AUTH_TOKEN) |
| SQLDelight driver | `sqldelight.sqlite.driver` (JDBC) → file in app data dir; `MobileAgentDatabase` factory unchanged |
| Preferences repos (Search/Language/Memory/Onboarding/TelemetryConsent/Clock) | File-based JSON via kotlinx.serialization (Search prefs already use single-blob JSON — pattern transfers) |
| `MemoryHeadroomProvider` | JVM heap / `OperatingSystemMXBean` free RAM |
| `ThermalStatusProvider` | **Stub** NOMINAL (no desktop thermal API); `ThermalBanner` simply never shows |
| Asset loading | new `ResourceLoader` interface (below) |

**`ResourceLoader` abstraction (cross-cutting):** `vocab.txt`, `locations.json`, `*_config.json`, `search_defaults.json` are Android assets opened via `context.assets` (e.g. `LiteRtClassifierEngine.kt:148`). Common `interface ResourceLoader { fun open(name): ... }` — Android: `context.assets.open`; desktop: classpath resource for small configs/vocab, filesystem for large downloaded models. Big artifacts (GGUF, ONNX) are **downloaded at runtime** into the app data dir (too large to bundle), same as Android downloads the LLM.

---

## Phase 7 — Desktop platform features

- **Voice:** promote `ChatSpeaker` + extract a `Dictation` interface (already clean; only `AndroidTtsSpeaker`/`SpeechDictation` are coupled). STT → **Vosk** (offline JNI, cross-platform) as `VoskDictation`. TTS → per-OS `ProcessBuilder` shell-out (`say`/`spd-say`/PowerShell SAPI) as `DesktopTtsSpeaker`, no-op fallback; keep the `isSpeaking` StateFlow for echo suppression.
- **Vision:** `ImagePreprocessor`→interface; desktop actual uses `javax.imageio.ImageIO`+`BufferedImage` decode/downscale→JPEG `ByteArray`→`HistoryMessage.imageBytes`. File chooser via `FileDialog`/`JFileChooser` behind a `FilePicker` interface. **RISK:** llama.cpp Gemma vision (mmproj) maturity uncertain — gate behind `InferenceConfig.enableVision`; ship text-only on desktop if unsupported.
- **Markdown/LaTeX:** `MarkdownMathText` is an `AndroidView`+Markwon+jlatexmath — not portable. Make it `expect/actual` composable `MarkdownMath(text, renderMarkdown)`: Android keeps Markwon verbatim; desktop uses a CMP markdown lib + JLaTeXMath-rendered-to-image. Reuse existing `LatexNormalizer.kt`; preserve the `renderMarkdown` gate semantics.
- **System tray:** Compose Desktop `Tray` in `:desktopApp`; window-close→hide to tray (Show/Pause queue/Quit menu). **Warm model:** keep `ModelHandle` resident while minimized; in-flight `generate()` coroutines keep running on a long-lived app scope (no foreground-service constraint like Android's `InferenceForegroundService`).
- **Queued agent-task system (new):**
  - `commonMain` model `QueuedTask(id, prompt, attachments, status, progress, createdAt, result)`.
  - `commonMain` `TaskQueue` — single-consumer coroutine pulling from a persisted queue, running tasks sequentially through one warm `AgentLoop` session; exposes `StateFlow<List<QueuedTask>>`.
  - **Persistence:** new `Tasks.sq` table alongside the existing five — append-only, but a real SQLDelight migration: add `.sqm` + regenerate the snapshot (the `verifyMigrations=true` gate, `shared/build.gradle.kts:133`, enforces it). Same table can later back an Android equivalent.
  - Tray shows queue depth + current progress; completion fires a desktop notification.
- **Notifications:** common `NotificationPresenter`; desktop via CMP `TrayState.sendNotification`. Alarm firing (`AlarmScheduler`) on desktop → coroutine `delay`-until-instant registry persisted in `ClockRepository` (preserve recurrence/stop semantics), replacing `AlarmManager`.
- **Telemetry:** interfaces (`AnalyticsSink`/`SafeCrashReporter`/`TelemetryCounters`) are common; Firebase impls are Android-only. Desktop → **Sentry** for crashes + file/OTel counter sink, behind the same interfaces, gated by `TelemetryConsentManager`. `TelemetryUploadWorker`→coroutine uploader.
- **Model download:** replace `ModelDownloadWorker` (WorkManager) with the coroutine `ModelDownloader` from Phase 4, surfacing progress through the tray + the shared `DownloadScreen`.

---

## Phase 8 — Packaging / distribution

- Compose Desktop `nativeDistributions` (jpackage) in `:desktopApp/build.gradle.kts`: Msi/Exe (Win), Dmg/Pkg (mac), Deb/Rpm (Linux). Requires a **CI matrix** (ubuntu/macos/windows runners) — jpackage builds per host OS.
- **llama.cpp natives:** ship a **CPU-default build that always works**; CUDA/Metal/Vulkan native bundles optional/separately-downloaded (CUDA is large + needs the user's NVIDIA driver — we don't ship the toolkit). Document GPU driver assumptions.
- Bundle small config assets as `:desktopApp` classpath resources; download large models (GGUF, ONNX) at first run.
- ONNX Runtime CPU bundled; `onnxruntime_gpu` optional.

---

## Phase 9 — UI cutover + release

- The Phase 3 UI move is done **screen-by-screen**, simplest first: `TodoManagementScreen` → both shells build → repeat (`Settings`, `Memory`, `History`, `Download`, `Onboarding`, `Alarm`), **Chat last** (MarkdownMath + pickers). `:androidApp` stays green throughout.
- Android shell shrinks to: `MainActivity`, `MobileAgentApplication` (Koin start), Android Koin module, Android-only services (clock receivers, foreground service), Android actuals.
- Bump `appVersionName` (`androidApp/build.gradle.kts:102`); tag **`v0.1.0`** at merge of `feature/desktop-cmp` → `main`.

---

## Risk register (highest first)

| Risk | Mitigation |
|---|---|
| **DI migration (Hilt→Koin)** touches everything | Hilt↔Koin coexistence bridge; migrate subsystem-by-subsystem with full Android tests between each |
| **llama.cpp Gemma vision** uncertain | De-risk text-first in Phase 0; gate behind `enableVision`; ship text-only if needed |
| **Native packaging + CUDA bundling** | CPU-default always works; GPU optional download; per-OS CI matrix |
| **MarkdownMathText / pickers** CMP migration | expect/actual renderer; CMP markdown + JLaTeXMath-to-image; Android path verbatim |
| **GGUF ≠ .litertlm** acquisition | separate desktop GGUF download spec; reuse portable `ModelDownloader` |
| **ONNX numeric parity** for classifier/embedder | validate vs existing Python reference fixtures; head-by-name; 128 seq len |
| **`Tasks.sq` migration** | new append-only table + `.sqm` + regenerated snapshot (`verifyMigrations` gate) |
| **llama.cpp cancellation** differs from `cancelProcess()` | `ensureActive()` between tokens + abort flag; dedicated single-thread ctx |

---

## Verification

- **Phase 0 (de-risk):** run `:desktopHarness` — confirm streamed tokens, cancellation aborts native decode, deterministic clock/todo short-circuits fire. This is the go/no-go gate.
- **Per-phase Android regression:** after each Koin/ViewModel/screen step, `./gradlew :androidApp:assembleDebug` + the existing Android unit + instrumentation suites (`ClassifierEndToEndTest`, `EmbedderEndToEndTest`, latency benchmarks) must stay green.
- **Desktop classifier/embedder:** numeric-parity test comparing `OnnxClassifierEngine`/`OnnxEmbedderEngine` outputs against `classifier-training/tests/fixtures/*` reference vectors.
- **Desktop e2e:** `./gradlew :desktopApp:run` — chat turn with GPU active (verify `activeAccelerator` reports CUDA/Metal), a search-grounded turn (greedy + history-drop), a deterministic weather/finance card, a voice round-trip, an image turn (or the documented "vision unavailable" state).
- **Tray + queue:** submit ≥2 queued tasks, minimize to tray, confirm sequential background execution, per-task progress, and completion notifications; confirm the warm model survives window close and an in-flight generation completes in the background.
- **Packaging:** produce and launch a native bundle per OS (deb/dmg/msi) on the CI matrix; verify first-run model download into the app data dir.

---

## Phase 0 — Status: COMPLETE (branch `feature/desktop-cmp`)

The headless de-risk slice is done and verified on Linux x86-64 (CPU).

**Landed:**
- `:shared` gains a `jvm("desktop")` target. The entire ~14k-LOC `commonMain` compiles for desktop with only 3 trivial JVM actuals (`Clock.desktop.kt`, `Locale.desktop.kt`, `WordPieceTokenizer.desktop.kt` — copies of the Android actuals).
- `shared/src/desktopMain`: `LlamaCppInferenceEngine` (de.kherud:llama 4.1.0), `DesktopHttpEngineFactory` (Ktor CIO + `ContentRedactor`), `NoOpClassifierEngine`, `NoOpEmbedderEngine`.
- New `:desktopHarness` (kotlin-jvm + application) wires the **real** `AgentLoop` + `PromptAssembler` + `PreflightRouter` + `SearchService` (search disabled) with an in-memory `MobileAgentDatabase` via the JDBC SQLite driver — also validating the SQLDelight JVM-driver seam. `Main.kt` runs a chat turn; `EngineSmoke.kt` is a low-token native-path probe.
- Catalog/root/settings: `ktor-client-cio`, `llama-jni`, `kotlin-jvm` plugin, `:desktopHarness` include.

**Verified:**
- `:shared:compileKotlinDesktop` ✓, `:desktopHarness` compiles + runs ✓.
- Native path (`EngineSmoke`, Qwen2.5-0.5B-Instruct GGUF): `libjllama.so` loads, streams tokens at ~25 tok/s CPU, terminal `Done`.
- **Full `AgentLoop` e2e:** a 582-token assembled structured prompt → "The capital of France is Paris." stopping at end-of-turn. Phase 0 exit criterion met.
- `:androidApp:assembleDebug` ✓ — Android unaffected (cardinal rule held).

**Findings carried into later phases:**
- `de.kherud:llama` `model.generate()` consumes `prompt`, NOT the `setMessages()` array — the chat template must be materialized via `model.applyTemplate(params)` first, else generation stops at 0 tokens. `LlamaCppInferenceEngine` does this on the structured path.
- The bundled `de.kherud:llama` artifact ships **CPU-only** natives; CUDA/Metal/Vulkan needs a GPU-classified artifact or a locally built `libjllama` (Phase 8 packaging). `activeAccelerator` is best-effort from `gpuLayers` intent — it cannot detect actual offload from Java, so it can report GPU on a CPU-only native (improve in Phase 4 if a probe surfaces).
- `de.kherud:llama` leaves native server threads alive after `close()`; CLI entrypoints must `exitProcess(0)`.
- Run real inference: `GEMMA_GGUF_PATH=/path/to/model.gguf ./gradlew :desktopHarness:run --args="your prompt"`.

---

## Phase 1 — Status: COMPLETE (branch `feature/desktop-cmp`)

Module restructure stood up as compiling shells; `:androidApp` untouched.

**Landed:**
- `:ui` — Compose Multiplatform library (1.10.3) with `android` + `jvm("desktop")` targets, depending on `:shared`. Holds `PlaceholderScreen()` (commonMain). Same KMP-android plugin combo as `:shared`.
- `:desktopApp` — Compose Desktop application (kotlin-jvm + compose), depends on `:shared` + `:ui`; `Main.kt` opens a window rendering `PlaceholderScreen`. `nativeDistributions` configured (Deb/Dmg/Msi, installer packageVersion 1.0.0 — MAJOR>0 required by the plugin, decoupled from the v0.1.0 release tag) for Phase 8.
- Catalog/root/settings: `compose-multiplatform` version + `org.jetbrains.compose` plugin alias; `:ui` + `:desktopApp` includes.

**Verified:** `:ui:compileKotlinDesktop` ✓, `:ui:compileAndroidMain` ✓, `:desktopApp:compileKotlin` ✓, `:androidApp:assembleDebug` ✓. (Desktop window launch is a manual check — CI sandbox is headless.)

**Findings:**
- The Compose *compiler* comes from Kotlin's `plugin.compose` (Kotlin 2.3.21); `org.jetbrains.compose` 1.10.3 supplies the multiplatform runtime + desktop packaging. They coexist with AGP 9's `com.android.kotlin.multiplatform.library`.
- `compose.material3` / `compose.ui` shorthand accessors are deprecated in CMP 1.10 (still functional) — switch to direct artifact deps when migrating real screens (Phase 3).
- **Pre-existing (NOT a Phase-0/1 regression):** `:shared:compileCommonMainKotlinMetadata` and the iOS-native/`commonize` path fail (e.g. `Dispatchers.IO`, `DOT_MATCHES_ALL`, `Volatile`, expect-enum exhaustiveness unresolved in pure-common). Verified failing identically at commit 322402e. The team builds `:androidApp` (compiles `androidMain` directly, bypassing metadata); the iOS target is a stub (Phase 2). **Do not run `:ui:assemble` / iOS / metadata tasks** until the iOS source-set is made common-clean. Desktop + Android paths are unaffected.

---

## Phase 2 — Status: DI FOUNDATION DONE (Koin); Android bridge + ViewModels still TODO

The Koin DI graph is stood up and **desktop is wired through it**; Android stays on Hilt, untouched and green (the coexistence bridge is deliberately deferred to a reviewable increment).

**Landed:**
- Koin 4.2.1 added (`koin-core` as `api` on `:shared` commonMain; `koin-android`/`koin-compose` catalog entries ready for later).
- `:shared` commonMain `di/AgentCoreModule.kt` — `agentCoreModule`: the platform-agnostic graph (`AgentClock`, `LocaleProvider`, `PromptAssembler`, `QueryRewriter`). Search-enablement policy is intentionally NOT here (kept per-platform).
- `:shared` desktopMain `di/DesktopModule.kt` — `desktopModule`: desktop bindings (`LlamaCppInferenceEngine`, NoOp classifier/embedder, `DesktopHttpEngineFactory`, JDBC `SqlDriver` + `MobileAgentDatabase` + `SearchCacheDao`, null Brave key / disabled client, minimal vocab+tokenizer, `PreflightRouter` + `SearchService` with search disabled — Phase-0 parity).
- `:desktopApp` `main()` calls `startKoin(agentCoreModule, desktopModule)` and force-resolves the agent core; `DI_CHECK=1` resolves + exits (headless smoke).

**Verified:** `:desktopApp:compileKotlin` ✓; `DI_CHECK=1 :desktopApp:run` → "Koin agent graph resolved OK" exit 0 (instantiates engine, in-memory DB+schema, router, search service) ✓; `:androidApp:assembleDebug` ✓ (koin-core now transitively on the Hilt app's classpath, no conflict).

**Phase 2b — bridge PROVEN (first subsystem):**
- `startKoin { androidContext(...); modules(agentCoreModule) }` in `MobileAgentApplication.onCreate` **before `super.onCreate()`** (Hilt injects the Application's `@Inject` fields during super, and several transitively resolve the agent-core singletons). Guarded with `GlobalContext.getOrNull()` for test re-creation. `koin-android` added to `:androidApp`.
- Four Hilt providers now delegate to Koin via `getKoin().get()` (single ownership): `PlatformModule.provideAgentClock`/`provideLocaleProvider`, `AgentModule.providePromptAssembler`, `ClassifierModule.provideQueryRewriter`. Their old param-injected bodies are gone; other Hilt consumers (SearchModule/MemoryModule inject `AgentClock`, `providePreflightRouter` injects `QueryRewriter`) transparently get the Koin instances.
- Verified: `:androidApp:assembleDebug` ✓ (Hilt graph validated with the bridge). On-device runtime check (launch + chat turn) is manual — no emulator in CI.

**Phase 2b progress — modules fully migrated onto the bridge (each verified by `:androidApp:assembleDebug`):**
- **Agent-core** (commit 2dae3f7): `AgentClock`, `LocaleProvider`, `PromptAssembler`, `QueryRewriter` → Koin `agentCoreModule` (commonMain).
- **PlatformModule** (commit 92b9ee1): `HttpEngineFactory`, `SecureStorage` (via `androidContext()`) → Koin `androidModule`. PlatformModule's 4 providers all delegate. Established `androidModule` (the Android counterpart of `desktopModule`).
- **DatabaseModule** (commit 8d4f693): `MobileAgentDatabase` (AndroidSqliteDriver, same `mobile_agent.db`) + the 4 query handles → `androidModule`; query handles resolve by type. `ConversationRepository` stays Hilt-constructed over Koin-backed queries.
- **Tokenizer/vocab + memoriesQueries** (commit f26cb7e): `WordPieceTokenizer` → shared `agentCoreModule` (resolves a per-platform `Vocab`); `Vocab` (real vocab.txt) + `memoriesQueries` → `androidModule`; `desktopModule` drops its now-redundant tokenizer binding. Bridged `ClassifierModule.provideVocab`/`provideWordPieceTokenizer` + `MemoryModule.provideMemoriesQueries`. (ClassifierModule/MemoryModule are now *partially* on Koin — coexistence is expected.)
- **Telemetry + system-state** (commit 11274dc): `InMemoryTelemetryCounters` (bound to both `TelemetryCounters` + `TelemetryFlusher`, shared instance) + `AndroidThermalStatusProvider` + `AndroidMemoryHeadroomProvider` → `androidModule`. Bridged the matching TelemetryModule/InferenceModule providers.
- **Engines** (commit 767d876): `InferenceEngine` (stub-vs-LiteRT via `USE_STUB_ENGINE`), `ClassifierEngine` + `EmbedderEngine` (Managed* wrappers, interface-typed) → `androidModule`. Bridged InferenceModule/ClassifierModule/MemoryModule engine providers. Now parallel to `desktopModule`'s engine bindings.
- **Memory subsystem** (commit cce0061): `MemoryConfig`, `SystemMemoryThresholds`, `MemoryStore`, `MemoryPreferences`, the detectors, `TempContextDateParser`, `MemoryRetriever`, `MemoryExtractor` → `androidModule`. `MemoryModule` is now fully delegating. (Orchestrators are pure commonMain — Android wiring for now; promote to shared when desktop enables memory.)
- **Search subsystem** (commit 552099b): `BraveKeyProvider`, default GENERAL `BraveSearchClient`, `SearchCacheDao`, and the four `SearchService` variants → `androidModule`. The 3 Hilt qualifiers (`@SportsSearch`/`@NewsSearch`/`@FinanceSearch`) become Koin `named("sports"/"news"/"finance")`; `SearchModule` fully delegating; `VerticalSearchModule` resolves them unchanged. `"BraveApi"` log tag preserved (#28).
- **Pre-flight router** (commit 6d6e8f1): `SearchSubtypeDetector` → `agentCoreModule`; `PreflightConfig` (asset) + `PreflightRouter` (per-platform, `searchAvailable = SearchService.isAvailable()`) → `androidModule`. `ClassifierModule` now fully delegating; `"ClassifierModule"` log tag preserved (#28).
- **Agent-graph collaborators** (commit 32b2a2c): Clock (repo/scheduler/notifications/service/tool-handler), Todo (repo + detectors/parser/formatter + tool-handler), `VerticalSearchDispatcher`, `SearchPreferencesRepository`, `DefaultSiteResolver`, `LocationCatalog`, `WeatherLocationResolver`, `OnboardingPreferences`, Weather/Stock formatters → `androidModule`. AgentModule/Clock/Todo/Preferences/Onboarding/VerticalSearch delegate. `provideAgentLoopFactory` stays Hilt but resolves all collaborators from Koin (migrates to common in Phase 3).
- **Remaining shared interfaces** (commit 02f2678): `ConversationRepository`, `LanguagePreferences`, `TranslationIntentDetector`, `TelemetryConsentManager` → `androidModule`.

### Phase 2b — COMPLETE (shared agent graph)

**Every commonMain-interface binding the cross-platform agent graph uses is now Koin-owned**, with `androidModule` mirroring `desktopModule`. Android stayed green at every commit; the desktop Koin graph resolves throughout.

The residual Hilt bindings are **not** part of the shared graph and are deferred to **Phase 3** (delete Hilt + ViewModels → `koinViewModel`, with desktop equivalents): `provideAgentLoopFactory` (capstone — moves to common); the Firebase telemetry pipeline (`AnalyticsSink`/`SafeCrashReporter`/`TelemetryUploader`/`TelemetryPayloadBuilder` → desktop Sentry, Phase 7); voice/UI (`TtsModule`, `ThemeModule`); lifecycle/download (`SessionModule`, `HttpModule`, `HfAuthTokenProvider`, `MainThreadProbe`); and the Android UI backup `@Binds` (`MemoryBackupOps`). These are Android-impl-only or Phase-3/Phase-7 concerns.

**Remaining Phase 2 (continue subsystem-by-subsystem, each gated by `:androidApp:assembleDebug` + tests):**
- Bigger subsystems: Memory (embedder, store, retriever, extractor), Classifier engines + `PreflightRouter`, Inference (`InferenceEngine`/session managers), and **Search** (largest — `SearchService` ×4 qualified `@NewsSearch`/`@SportsSearch`/`@FinanceSearch`, Brave clients, vertical adapters/dispatcher; needs Koin *named* definitions to replace Hilt qualifiers). The real Android engines + search-enabled `PreflightRouter`/`SearchService` land in `androidModule`.
- Flesh out the full graph fidelity (vertical search adapters, all preferences repos, telemetry) currently simplified on desktop.
- On-device runtime verification of the bridge (launch + chat turn) — pending, needs a device/emulator.
- **3 — ViewModels:** move to `lifecycle-viewmodel-compose` KMP + `koinViewModel`, then delete Hilt once `:androidApp` is a thin shell.

---

## Phase 3 — Status: HILT ELIMINATION COMPLETE (branch `feature/desktop-cmp`)

**Scope decision (user-confirmed):** Phase 3 here covers the *Hilt elimination* — all ViewModels → Koin, `AgentLoopFactory` lifted to commonMain, every remaining Android-only service bound in `androidModule`, then **Hilt deleted**. `:androidApp` is now Hilt-free. The screen-into-`:ui` relocation + `expect`/`actual` composables (MarkdownMath, pickers, ImagePreprocessor, voice) stay deferred to **Phase 7** (desktop actuals) + **Phase 9** (UI cutover, screen-by-screen, Chat last) — they need the desktop runtime to validate against. ViewModels remain in `:androidApp` with their Android types intact (Koin-resolved via `androidContext()`); they migrate to `:ui` in Phase 9.

Each increment was additive; `:androidApp:assembleDebug` stayed green throughout. Commits: `9b9211a`, `a3579bd`, `24496d7`, `2c9c21b`, `3115536`.

**Increment 5 — delete Hilt (`3115536`):** entry points off Hilt — `MobileAgentApplication` drops `@HiltAndroidApp`, its 8 `@Inject` fields become Koin `by inject()`; `MainActivity`/`SpikeActivity` drop `@AndroidEntryPoint` (use `koinViewModel()` / `by viewModel()`); clock receivers + `AlarmFiringService` implement `KoinComponent` + `by inject()`; both workers drop their `@EntryPoint` interfaces and `getKoin().get()` their dep. Last `@Inject` services (watchdogs, `HandlerMainThreadProbe`, `ModelDownloader`) + the model-download OkHttp client (Koin `named("modelDownloadHttp")`), `HfAuthTokenProvider`, and the telemetry sink/payload-builder/uploader bound in `androidModule`. **All 18 `app/di` Hilt modules deleted** (`AndroidKoinModule.kt` is the sole DI file). Removed the hilt + ksp plugins/deps from `androidApp/build.gradle.kts`, the root build, and the catalog (ksp was Hilt-only). Verified: `assembleDebug` (no hilt/ksp tasks remain) + `testDebugUnitTest` + `DI_CHECK=1 :desktopApp:run` all green.

**Increment 4 — all ViewModels → Koin (`2c9c21b`):** stripped `@HiltViewModel`/`@Inject` from the 12 remaining VMs; `viewModelOf(::X)` for all-Koin-dep VMs, explicit `viewModel { }` for `ChatViewModel`/`SettingsViewModel` (Context) + `SpikeViewModel` (Application). Every `hiltViewModel()` callsite → `koinViewModel()`; `SpikeActivity` `by viewModels()` → Koin `by viewModel()`. `@HiltViewModel` fully gone.

**Increment 3 — app-shell services → Koin (`24496d7`):** bound the services VMs consume (`InferenceSessionManager`, `ForegroundServiceController`, `AuxModelLifecycleCoordinator`, `SystemMemoryMonitor`, `ModelInventory`, `ModelDownloadController`, `MemoryBackupController`, `TtsPreferences`/`ChatSpeaker`/`ThemePreferences`/`SafeCrashReporter`) in `androidModule` with single ownership; the Hilt `@Provides`/`@Binds` delegated `getKoin().get()` (Phase 2b pattern) until deletion.

**Increment 2 — `AgentLoopFactory` → commonMain (`a3579bd`):** interface + Koin binding moved to `:shared` `com.contextsolutions.mobileagent.di` (`agentCoreModule`); required collaborators via `get()`, optional ones via `getOrNull()` so the search/memory-disabled desktop graph still builds a loop. New `AgentLogger` fun interface keeps the `"AgentLoop"` tag (#28) per-platform. Pinned desktop `kotlinx-datetime` to 0.6.1 (Compose Material3 1.9.0 drags in 0.7.x which relocated `kotlinx.datetime.Clock`).

**Increment 1 — `TodoViewModel` → `koinViewModel` (proof):**
- Catalog: added `koin-compose-viewmodel` (multiplatform `koinViewModel()` + transitively `koin-core-viewmodel`'s `viewModelOf` DSL); wired `implementation(libs.koin.compose.viewmodel)` into `:androidApp`.
- `androidModule` (`AndroidKoinModule.kt`): `viewModelOf(::TodoViewModel)` (binding lives here while the screen is still in `:androidApp`; moves to a `:ui` Koin module when the screen migrates in increment 3-onward). `TodoViewModel` dropped `@HiltViewModel`/`@Inject` (plain ctor; `: ViewModel()` is the multiplatform androidx lifecycle base). Its one dep (`TodoRepository`) was already Koin-owned.
- Both callsites flipped `hiltViewModel()` → `koinViewModel()`: `TodoManagementScreen.kt:74`, `ChatScreen.kt:127` (other VMs on that screen stay on Hilt — coexistence is fine; `koinViewModel()` resolves via the Koin GlobalContext started in `MobileAgentApplication.onCreate`).
- **Verified:** `:androidApp:assembleDebug` ✓; `DI_CHECK=1 :desktopApp:run` → "Koin agent graph resolved OK" ✓.

**Still pending (Phase 7 / Phase 9):** on-device runtime verification of the Koin graph (launch + chat turn + alarm fire + model download — no emulator in CI, so it's the user's manual check); then the `expect`/`actual` composables (Phase 7) and the screen-by-screen move into `:ui` with `ViewModels` migrating off their Android types (Phase 9, Chat last).

---

## Phase 4 — Status: COMPLETE (branch `feature/desktop-cmp`)

`LlamaCppInferenceEngine` matured from the Phase-0 stub to a full `InferenceEngine` implementation, plus the desktop GGUF acquisition path. Commits `cac0a8a`, `5620d39`, `6d009b0`. (Param mapping, chat template via `applyTemplate`+Jinja, streaming, and between-token cancellation already landed in Phase 0 — Phase 4 closed the remaining gaps.)

- **Contract completeness (`cac0a8a`):** `generate()` now emits exactly one terminal `Done` **or** `Error` (was letting raw exceptions escape) — parity with the Android `LiteRtInferenceEngine` so `AgentLoop` can surface the friendly message / attempt tool-marker recovery. `CancellationException` is re-thrown after `iterator.cancel()` so collector cancellation still aborts the native decode. Also fixed a latent harness break (Kotlin 2.3 rejects the inferred `Nothing` return of `fun main() = runBlocking { … exitProcess(0) }` → pinned `: Unit`).
- **Backend logging (`5620d39`):** added a load-time `logger` + host-OS detection of the *intended* GPU backend (Metal / CUDA / Vulkan) for diagnostics. The bundled `de.kherud:llama` artifact is CPU-only and actual layer offload can't be queried from Java (Phase 0 finding), so `activeAccelerator` stays best-effort — a real probe waits for the GPU-classified natives in **Phase 8**.
- **GGUF download (`6d009b0`):** `DesktopModelSpec` / `DesktopModelInventory` (OS-aware app-data dir, `.partial` layout, size-based presence) + `DesktopModelDownloader` (resumable, SHA-256 + size verify, free-space pre-check, atomic promote, chunked cancellation, optional HF token). Uses JDK `HttpURLConnection` (stable Range/streaming; Ktor stays on the search path). Self-contained in `desktopMain` (no Android churn). The `DEFAULT` spec ships the GGUF URL but leaves `sha256`/`sizeBytes` blank (`isConfigured=false`) — the downloader refuses an unverifiable write until the operator fills verified coordinates (same BYO policy as Android's `secrets.properties`). Bound in `desktopModule`; `DI_CHECK` resolves it.

- **Binding fork swap (`cd9906d`):** `de.kherud:llama` 4.1.0 (llama.cpp b4916) only knows `gemma3` — a Gemma 4 GGUF failed `unknown model architecture: 'gemma4'`, and de.kherud's newest (4.2.0) was still too old. Switched to the **bernardladenthin fork `net.ladenthin:llama` 5.0.1** (Maven Central, bundles llama.cpp **b9151**), which loads `gemma4`. The fork **renames the package** `de.kherud.llama` → `net.ladenthin.llama` (5 imports updated; API otherwise identical). `DesktopModelInventory.DEFAULT` now points at a Gemma-4-E4B-it Q4_K_M GGUF with operator-verified `sha256`/`sizeBytes`. **Verified on-device:** real gemma4 generation streams a full turn through the desktop `AgentLoop`. (Pinning an exact llama.cpp build / GPU natives still needs a custom `libjllama` — Phase 8.)

**Verified:** `:shared:compileKotlinDesktop` + `:desktopHarness:compileKotlin` + `DI_CHECK=1 :desktopApp:run` green at every commit; real gemma4 streamed generation via `GEMMA_GGUF_PATH=… :desktopHarness:run`. The engine read-path consumes `inventory.localFile()` in Phase 7 (tray/chat), replacing the harness env var.

**Deferred to later phases (unchanged):** ONNX classifier/embedder (Phase 5); the remaining `desktopMain` seams — file-backed DB path, secure storage, preferences, `ResourceLoader` (Phase 6); GPU-native packaging + real accelerator probe (Phase 8).

---

## Phase 5 — Status: COMPLETE pending operator parity run (branch `feature/desktop-cmp`)

Desktop classifier + embedder via ONNX Runtime (Java). All four increments landed and compile-verified; the one remaining step is the operator running the export + `:shared:desktopTest` against real `.onnx` artifacts on a box with the v1.0 checkpoint (no checkpoint/GPU/models in CI — same manual-verification boundary as the Phase-4 GGUF inference). Four verified increments:
**(1) ORT dep + engine impls → (2) ONNX export pipeline → (3) wire into `desktopModule` (real vocab + model path) → (4) numeric-parity test vs Python fixtures.**

**Increment 1 — ORT dep + engines (commit `8af78be`):**
- Catalog + `:shared` desktopMain: `com.microsoft.onnxruntime:onnxruntime` 1.20.0 (CPU natives bundled, headless-safe; `onnxruntime_gpu` is the optional CUDA-EP swap, gated like the LLM GPU path).
- `OnnxClassifierEngine : ClassifierEngine` (desktopMain `classifier/`) — re-export of the shared DistilBERT encoder + 3 task heads. Resolves the five tensors **by explicit name** (`input_ids`/`attention_mask`; `preflight_logits`/`presence_logits`/`category_logits`) via `OrtSession.Result.get(name)`, never positional (invariant #12). Where the `.tflite` engine disambiguates heads by shape because the runtime permutes names↔indices, the ONNX export sets stable output names so by-name resolution is exact. Seq-len 128 + int64 inputs (#15); `ClassifierOutput.init` re-checks `[3]/[2]/[6]` at warmup. CUDA EP first → CPU fallback (`addCUDA` throws on the CPU-only artifact = clean fallback). Non-throwing `warmUp` returns `null` on a missing model file / init failure → `PreflightRouter` degrades to Gemma (PRD §3.2.1). `Dispatchers.IO` under a `Mutex`, mirroring the Android engine.
- `OnnxEmbedderEngine : EmbedderEngine` (desktopMain `memory/`) — MiniLM with mean-pool + L2-norm baked in; single output read generically (`outputNames.single()`); `EmbedderOutput.init` enforces 384-dim; internal `WordPieceTokenizer` tokenisation (#13). Same accelerator/threading/failure semantics.
- **NOT yet wired:** `desktopModule` keeps the NoOp bindings (so runtime + `DI_CHECK` are unchanged) until `ct-export-onnx` exists and a real `vocab.txt` + model-path source land in increment 3.

**Verified:** `:shared:compileKotlinDesktop`, `:desktopHarness:compileKotlin`, `DI_CHECK=1 :desktopApp:run` all green. (Real ONNX inference + numeric parity are the operator's manual checks — no trained checkpoint / `.onnx` files / GPU in CI; same pattern as the Phase-4 GGUF path.)

**Increment 2 — ONNX export pipeline (`classifier-training/`):**
- `ct-export-onnx` (`training/export_onnx.py`, registered in `pyproject.toml`) — DistilBERT shared encoder + 3 heads → ONNX via `torch.onnx.export`. Explicit names `input_ids`/`attention_mask` (int64) + `preflight_logits`/`presence_logits`/`category_logits`, matching `OnnxClassifierEngine`'s by-name dispatch constants (invariant #12). Fully static `[1, 128]` (no `dynamic_axes`) — seq length baked (#15). FP32 default; `--int8` = ONNX Runtime weight-only dynamic quant (the ONNX parallel to `ai-edge-quantizer`). Emits `tests/fixtures/classifier_onnx_canonical_outputs.json` (per-string logits + probs over 10 classification-meaningful probes) for the parity test — the `.tflite` side never had a logits fixture. `--compare-tflite PATH` optionally cross-checks the re-export vs the shipped `.tflite` via `ai-edge-litert` (max-abs-logit-diff; skips if unavailable).
- `export_minilm_onnx.py` (`scripts/`, mirrors `export_minilm_litert.py`) — same `ExportableMiniLMEncoder` (mean-pool + L2-norm baked), `torch.onnx.export`, single output `sentence_embedding` (engine reads it generically). `--int8` dynamic quant; emits `embedder_onnx_canonical_outputs.json` over the **identical 10 strings** as the litert fixture so `--compare-litert PATH` (and the eye) can diff ONNX-vs-`.tflite` vectors string-for-string (min cosine ≈ 1.0).
- New ONNX fixtures use **distinct filenames** (`*_onnx_*`) so the Android `.tflite` fixtures (`embedder_canonical_outputs.json`, consumed by `EmbedderEndToEndTest`) stay byte-untouched.
- Parity strategy: the Kotlin test (inc 4) asserts engine-output == ONNX fixture (Kotlin-ORT vs Python-ORT on the same graph → tight tolerance, validates by-name dispatch + tokenizer + tensor layout). Re-export *fidelity* vs the shipped `.tflite` is the optional `--compare-*` Python-side check (looser, INT8 drift).
- `[training]` extra already has `onnx` + `onnxruntime` (incl. `.quantization`) — no dependency change.

**Verified:** `python -m py_compile` + `ruff check` clean on both scripts. The actual export + numeric parity run on the operator's box (RTX 5090, v1.0 checkpoint, `.onnx` artifacts) — no checkpoint/torch/GPU in CI.

**Increment 3 — wire the ONNX engines into `desktopModule` (commit `7b57b20`):**
- `vocab.txt` bundled as a `shared/src/desktopMain/resources/` classpath resource (byte-identical to the Android asset — invariant #13; confirmed in `shared-desktop.jar` at root, 30,522 lines). `DesktopVocabLoader.loadOrNull()` reads it; the `desktopModule` `Vocab` binding now serves the real vocab, falling back to the 5-token stub only if the resource vanishes (the tokenizer is a no-op while there's no model to feed, so the graph still resolves). This is the Phase-6 `ResourceLoader` "classpath resource for small configs/vocab" rule applied early because the wired engines need a real vocab the moment they tokenize.
- `DesktopAuxModels` (desktopMain `inference/`) resolves the two `.onnx` paths from the app-data `models/` dir (mirroring the GGUF) or a `MOBILEAGENT_{CLASSIFIER,EMBEDDER}_ONNX` absolute-path env override — the "simple path/env" the plan sanctions before the full Phase-6 download/`ResourceLoader` wiring.
- `desktopModule` binds `OnnxClassifierEngine` + `OnnxEmbedderEngine` (replacing the NoOp bindings). Construction never touches ORT or the filesystem (deferred to `warmUp`), and `warmUp` returns `null` on a missing `.onnx`, so the agent degrades exactly as the NoOp engines did (PreflightRouter → Gemma; memory → no-op) and `DI_CHECK` stays green without the models present. **Search stays disabled** (Brave key + file-backed DB are Phase 6).
- The NoOp engine classes are **kept** — the Phase-0 `:desktopHarness` still hand-wires `NoOpClassifierEngine`; only the production `desktopModule` bindings changed.

**Verified:** `:shared:compileKotlinDesktop`, `:desktopHarness:compileKotlin`, `DI_CHECK=1 :desktopApp:run` all green. Android untouched (desktopMain + a new desktop resource only).

**Increment 4 — ONNX numeric-parity test (commit `aa335a0`):**
- New `:shared` `desktopTest` source set (`kotlin("test")`, `coroutines-test`, `serialization-json`, runtime `onnxruntime`) + a `generated/onnxParityFixtures` test-resources dir, staged by a `collectOnnxParityFixtures` `Copy` task from `classifier-training/tests/fixtures` (mirrors androidApp's `collectClassifierTestResources`). The Copy tolerates the not-yet-generated sources (`NO-SOURCE`) until the operator runs the exporters.
- `OnnxEngineParityTest` (desktopTest `onnx/`) drives the real `OnnxClassifierEngine`/`OnnxEmbedderEngine` over the canonical strings and asserts Kotlin-ORT outputs == the Python-ORT reference (`classifier_onnx_`/`embedder_onnx_canonical_outputs.json`) within `1e-3`. Validates by-name head dispatch (#12), `[1,128]` int64 layout (#15), and that the shared `WordPieceTokenizer` + bundled `vocab.txt` reproduce the Python tokenisation (#13). Fixture data classes use `@SerialName` on the `*_logits` fields + `ignoreUnknownKeys` (drops the diagnostic `*_probs`/`vector_norm`/metadata).
- **CI-skip contract:** each test no-ops with a printed `SKIP` when its `.onnx` is absent. Operator runs it by placing the `.onnx` in the app-data `models/` dir or setting `MOBILEAGENT_{CLASSIFIER,EMBEDDER}_ONNX`.

**Verified:** `:shared:compileTestKotlinDesktop` green; `:shared:desktopTest` runs both tests green (skipped — no `.onnx`); `collectOnnxParityFixtures` `NO-SOURCE` (no failure on absent fixtures). The three standard gates unaffected (test-only changes).

### Operator run-book (Phase 5 manual verification)

On a box with the v1.0 checkpoint + the `[training]` venv:
```bash
cd classifier-training && pip install -e .                    # registers ct-export-onnx
ct-export-onnx --ckpt eval/runs/<ts>/best.pt \
    --output ../models/preflight_memory_shared_v1.0.0.onnx \
    --compare-tflite ../models/preflight_memory_shared_v1.0.0_int8.tflite
python scripts/export_minilm_onnx.py \
    --compare-litert ../models/all-MiniLM-L6-v2_int8.tflite
# point the test at the freshly exported FP32 .onnx (or use --int8 + matching names)
cd ../android-app
MOBILEAGENT_CLASSIFIER_ONNX=$PWD/../models/preflight_memory_shared_v1.0.0.onnx \
MOBILEAGENT_EMBEDDER_ONNX=$PWD/../models/all-MiniLM-L6-v2.onnx \
    ./gradlew :shared:desktopTest --tests '*OnnxEngineParityTest*'
```
Expect: the `--compare-*` lines print near-zero logit diff / ≈1.0 cosine (re-export fidelity vs the shipped `.tflite`), and `desktopTest` asserts Kotlin-ORT == Python-ORT within `1e-3` (runtime-wiring parity). Then commit the two generated `*_onnx_canonical_outputs.json` fixtures so future CI has them on the classpath.

**Next:** Phase 6 — `desktopMain` seams (file-backed DB path, secure storage, preferences, the `ResourceLoader` abstraction that subsumes the early `DesktopVocabLoader`/`DesktopAuxModels` shortcuts), then flip search on with the real Brave client + key provider.

---

## Phase 6 — Status: COMPLETE (branch `feature/desktop-cmp`)

`desktopMain` platform seams + flipping search on. **All five increments landed**; the desktop agent graph is now near-complete (search + memory + todo live, all platform seams in place). Every Phase-6 change was `desktopMain`/`desktopTest`/desktop-resources/`build.gradle` (desktopTest deps) only — **no `commonMain`/`androidMain` touched**, so Android is unaffected by construction; confirmed with a full `:androidApp:assembleDebug` at phase end. Each increment kept the three desktop gates green.

**Increment 1 — file-backed SQLite (commit `28b4b28`):** `DesktopDatabaseFactory` (desktopMain `platform/`) replaces the Phase-2 in-memory `JdbcSqliteDriver` with a persistent driver at `<app-data>/mobile_agent.db`. SQLDelight 2.0.2's `JdbcSqliteDriver` has no schema-aware ctor, so create/migrate is driven off `PRAGMA user_version`: fresh file → `Schema.create` + stamp `Schema.version`; older → `Schema.migrate` (walks the `.sqm` files) + restamp; current → open as-is. Verified: 3 desktop gates green; DI_CHECK run 1 → "created schema v7", run 2 → "opened schema v7" (no table-exists on reopen); `mobile_agent.db` written, `user_version=7`.

**Increment 2 — desktop SecureStorage (commit `181ede7`):** `DesktopSecureStorage` (desktopMain `platform/SecureStorage.desktop.kt`) — a password-protected **PKCS#12 keystore** at `<app-data>/secrets.p12`, the desktop counterpart of Android's `EncryptedSharedPreferences`. Secrets are stored as **PBE `SecretKeyEntry`s**: a plain `SecretKeySpec("raw")` is rejected by PKCS#12 ("unrecognized algorithm name"), and PBE passwords must be ASCII, so each value is `Base64(UTF-8)` → wrapped as a PBE key → recovered via `getKeySpec(PBEKeySpec)`; any length / Unicode round-trips. No new dependency (pure JDK `java.security`/`javax.crypto`). **Security tier = the plan's fallback:** encrypted-at-rest, store password from a stable per-user value or the `MOBILEAGENT_KEYSTORE_PASSWORD` override; the OS-keyring hardening (`java-keyring`) is deferred to keep this dependency-free + headless-safe. Bound in `desktopModule`; the search-enabled `BraveKeyProvider` (commonMain `DefaultBraveKeyProvider`, reusable as-is) that reads it lands in the search-on increment. `DesktopSecureStorageTest` (desktopTest) round-trips put/get/contains/remove + reopen-persistence + unicode/long values — runs in CI. Verified: 3 desktop gates green; desktopTest 5/5 (SecureStorage 3 pass, parity 2 skip).

**Increment 3 — desktop ResourceLoader + bundled configs (commit `baac192`):** `DesktopResources` (desktopMain `platform/`) — classpath loader (`openOrNull`/`readTextOrNull`). The 5 config assets bundled as `shared/src/desktopMain/resources/` (byte-identical to androidApp; confirmed in the jar): `preflight_config.json`, `search_defaults.json`, `locations.json`, `memory_config.json`, `system_memory_config.json`. `DesktopVocabLoader` reimplemented over it. `PreflightConfig` now loads from `preflight_config.json` (invariant #14), DEFAULT only on failure. The cross-platform `ResourceLoader` interface is deferred to Phase 9 (when shared `:ui` screens need it) — keeping Phase 6 desktop-only.

**Increment 4 — desktop preference repos (commit `ddce997`):** `DesktopJsonStore` (synchronized JSON string-map file, the desktop SharedPreferences pattern) + `DesktopMemoryPreferences` + `DesktopSearchPreferencesRepository` (mirrors Android's DataStore impl: `location_json`/`vertical_prefs_json` blobs, `DefaultSiteResolver` default-seeding + merge, `MutableStateFlow` for `flow()`). Bound `DefaultSiteResolver` (from `search_defaults.json`), `SearchPreferencesRepository`, `MemoryPreferences`. The UI/telemetry/clock-coupled prefs (Onboarding/Language/TelemetryConsent/Clock) follow in Phase 7 with their consumers (no dead bindings). `DesktopPreferencesTest` runs in CI.

**Increment 5 — flip search ON + memory + todo (capstone, commit `00415f2`):** the desktop agent graph now mirrors androidModule's search/memory/todo wiring.
- **Search ON:** `DefaultBraveKeyProvider(SecureStorage, BRAVE_API_KEY env fallback)`; the four `SearchService` variants (default/sports/news/finance) with per-vertical Brave clients + cache namespaces (#37), enabled gate reading `SecureStorage` `SEARCH_ENABLED` (default on); `PreflightRouter` `searchAvailableProvider = { searchService.isAvailable() }` + shared `SearchSubtypeDetector` + `[ClassifierModule]` tag (#28); `VerticalSearchDispatcher` over the 4 services; `LocationCatalog`/`WeatherLocationResolver`/Weather+Stock formatters. No key ⇒ `isAvailable()` false ⇒ search silently skipped. **Gotcha:** Brave clients use `get<HttpEngineFactory>()` explicitly — the `internal` `HttpClient` ctor is visible inside `:shared` (unlike `:androidApp`), so a bare `get()` is ambiguous.
- **Memory ON:** `MemoryConfig` (asset), `SqlDelightMemoryStore`, the two detectors, `TempContextDateParser`, `MemoryRetriever`, `MemoryExtractor` (over the live Phase-5 embedder + file DB + `MemoryPreferences`).
- **Todo tool:** `SqlDelightTodoRepository` + detectors/parser/formatter + `TodoToolHandler`. Added `memoriesQueries`/`todosQueries` DB handles. Telemetry counters stay `NoOp` (upload = Phase 7).
- **Deferred to Phase 7** (need platform services/UI): clock tool (desktop `AlarmScheduler`), thermal/headroom providers, telemetry upload, UI/onboarding/language/consent prefs.

**Verified:** 3 desktop gates green at every commit; `DI_CHECK` resolves the full search+memory+todo+vertical graph (`[DB] opened schema v7`); `:shared:desktopTest` 8/8 (6 pass + 2 parity skip); **`:androidApp:assembleDebug` green** (cardinal rule — Android untouched, desktop-only changes).

**Operator note (search/memory on desktop):** drop the exported `.onnx` models in the app-data `models/` dir (or set `MOBILEAGENT_{CLASSIFIER,EMBEDDER}_ONNX`), set a Brave key via `BRAVE_API_KEY` (or `SecureStorage`), and the GGUF — then the desktop agent runs the full pre-flight → vertical-search → memory loop. App-data dir: `~/.local/share/MobileAgent/` (Linux), `~/Library/Application Support/MobileAgent/` (macOS), `%LOCALAPPDATA%\MobileAgent\` (Windows).

**Next:** Phase 7 — desktop platform features (voice/vision/markdown `expect`/`actual`, system tray + warm-model background inference, the queued agent-task system, Sentry telemetry, the deferred clock/thermal/telemetry/UI-prefs seams).

---

## Phase 7 — Status: COMPLETE (branch `feature/desktop-cmp`)

Desktop platform features + the seams Phase 6 deferred. Increment order (lowest-risk seams/telemetry first, the tray + `Tasks.sq` task queue as the headline, the `expect`/`actual` UI work — markdown/voice/vision — last so the Android cardinal-rule surface is disturbed only after the desktop-only core is solid):

1. ✅ system-state + remaining prefs seams · 2. clock subsystem (`DesktopClockRepository`/`DesktopAlarmScheduler` + `NotificationPresenter`) · 3. telemetry → Sentry · 4. `Tasks.sq` migration + `TaskQueue` · 5. system tray + warm-model background · 6. model download via tray · 7. Markdown/LaTeX `expect`/`actual` · 8. voice I/O (`Dictation`/Vosk/`DesktopTtsSpeaker`) · 9. vision (`ImagePreprocessor`/`FilePicker`, gated behind `enableVision`).

**Increment 1 — deferred system-state + UI/first-run preference seams (all `desktopMain`):**
- **System-state:** `DesktopThermalStatusProvider` always reports `ThermalStatus.NONE` — desktops expose no portable thermal-throttle API and the gates (`isThrottling`/`isBlocking`) were built for 8 GB-Pixel-7 LMKD pressure (invariant #4), so no thermal gate fires on mains-powered hardware. `DesktopMemoryHeadroomProvider` maps to OS free physical RAM via `com.sun.management.OperatingSystemMXBean.getFreePhysicalMemorySize()` (JDK 17 name; renamed `getFreeMemorySize()` only in 19+), the JVM analogue of Android's `ActivityManager.availMem`, with a `Runtime` heap-headroom fallback for a non-HotSpot JVM. Consumed by the Phase-7 warm-model/tray path.
- **Preferences:** `DesktopOnboardingPreferences` / `DesktopLanguagePreferences` / `DesktopTelemetryConsentManager` — file-JSON via `DesktopJsonStore` (one file per repo under the app-data dir: `onboarding_prefs.json` / `language_prefs.json` / `telemetry_consent.json`), each mirroring its Android `SharedPreferences*` counterpart (`MutableStateFlow` seeded from disk, idempotent writes, default OFF for telemetry consent per PRD §3.2.1). All bound in `desktopModule`.
- **Verified:** `:shared:compileKotlinDesktop` + `:desktopHarness:compileKotlin` + `DI_CHECK=1 :desktopApp:run` (`[DB] opened schema v7`, "resolved OK") + **`:androidApp:assembleDebug`** all green. No `commonMain`/`androidMain` touched — the three interfaces already existed in `commonMain`; only `desktopMain` impls + the `desktopModule` bindings were added.

**Increment 2 — clock subsystem on desktop (`AlarmManager` → coroutine registry):**
- **`NotificationPresenter` seam (NEW `commonMain` interface):** `com.contextsolutions.mobileagent.notification` — `present(AppNotification)` / `dismiss(id)`, `AppNotification(id,title,body,kind,ongoing)`, `NotificationKind{ALARM,TIMER,TASK,INFO}`. The cross-platform notification contract the clock fires (and the later task-queue completion) surface through. Android keeps its existing `ClockNotifications` and does **not** bind this seam yet (adopts it in the Phase-9 UI cutover), so Android is unaffected.
- **`DesktopNotificationPresenter` (`desktopMain`):** logging placeholder so the headless graph fires end-to-end; the system-tray increment overrides this binding in `:desktopApp` with a `TrayState.sendNotification`-backed presenter.
- **`DesktopClockRepository` (`desktopMain`):** file-JSON via `DesktopJsonStore` (`timers_json`/`alarms_json` in `clock_prefs.json`), mirroring Android's `SharedPreferencesClockRepository` (StateFlow seeded from disk, corrupt-blob-tolerant). The persisted rows are the scheduler's source of truth.
- **`DesktopAlarmScheduler` (`desktopMain`):** the coroutine `delay`-until-instant registry replacing `AlarmManager`. Per armed timer/alarm a coroutine sleeps to the wall-clock instant then drives the same firing contract as Android's `ClockEventReceiver` — reads the entry, calls `ClockService.onTimerFired`/`onAlarmFired` (deletes one-shots, **re-arms recurring** by re-entering `scheduleAlarm`), and presents a notification. **Drift-tolerant:** the wait is chunked (60 s) and recomputed against `AgentClock` each chunk so a sleeping laptop still fires promptly on wake. No own persistence — empty registry on start + `ClockService.rearmAll()` (an app/tray-increment startup call) re-creates fires, the desktop analogue of Android's boot receiver. `clockServiceProvider` is **lazy** (`{ get<ClockService>() }`) to break the `ClockService`↔scheduler DI cycle. Ringing alarms = an `ongoing` notification dismissed by `stopFiringAlarm` (the looping-sound + snooze/off actions are a richer tray-presenter's job).
- **Wired in `desktopModule`:** `NotificationPresenter`, `ClockRepository`, `AlarmScheduler`, `ClockService`, `ClockToolHandler` — so the desktop `AgentLoop` gains the six clock tools (`AgentLoopFactory` pulls `ClockToolHandler` via `getOrNull`).
- **Verified:** all four desktop gates + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green. `DI_CHECK` now force-resolves the full clock chain through `AgentLoopFactory`.

**Increment 3 — telemetry → Sentry + file sink (opt-in, consent-gated):**
- **Sentry dep:** catalog `sentry = "8.43.0"` + `sentry-jvm = io.sentry:sentry`, added to `:shared` `desktopMain` only (Firebase is Android-only, #23). Resolves from Maven Central.
- **`SentrySafeCrashReporter` (`desktopMain` `observability/`):** Sentry-backed `SafeCrashReporter`. Keeps the **redaction-at-callsite** discipline of `FirebaseSafeCrashReporter` (#24) — `ContentRedactor.redactThrowable` (→ `RedactedThrowable`, scrubbed message + preserved stack/class) before `captureException`; `redact` on breadcrumbs/tags — even though Sentry has a `beforeSend` hook (defense in depth). Consent-gated via an `AtomicBoolean` seeded from `TelemetryConsentManager` + `setCollectionEnabled`. **DSN-gated:** initialises only with `SENTRY_DSN` present; otherwise degrades to a local logger with no network call (CI / fresh-install safe; real upload is an operator check).
- **`FileAnalyticsSink` (`desktopMain` `telemetry/`):** the file/JSONL counter sink — appends `{name, params, ts}` lines to `<app-data>/telemetry/events.jsonl`. Egress chokepoint (#27); only the fixed counter/latency names + numeric values from `TelemetryPayloadBuilder` (never user text); errors absorbed silently per the `AnalyticsSink` contract. An OTel/HTTP exporter can later swap in behind the same interface.
- **`InMemoryTelemetryCounters` (`desktopMain` `telemetry/`):** verbatim copy of the Android impl (pure JVM — `ConcurrentHashMap`/`AtomicLong`/`Dispatchers.IO`, depends only on the shared `TelemetryAggregateQueries`), the same desktop-actual-duplication precedent as `Clock.desktop.kt`. Bound to both `TelemetryCounters` (pulled by `AgentLoopFactory` — desktop now records counts) + `TelemetryFlusher`.
- **`DesktopTelemetryScheduler` (`desktopMain` `telemetry/`):** the coroutine that replaces Android's `TelemetryUploadWorker` — one long-lived loop calling `TelemetryUploader.upload()` every 24 h (consent gate lives inside `upload()`). Not started by DI; the app/tray increment `start()`s it alongside `ClockService.rearmAll()`.
- **Wired in `desktopModule`:** `telemetryAggregateQueries` handle, the counters (both interfaces), `AnalyticsSink`, `SafeCrashReporter`, `TelemetryPayloadBuilder`, `TelemetryUploader`, `DesktopTelemetryScheduler`.
- **Verified:** all four desktop gates + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green (catalog addition + `desktopMain`-only dep — Android untouched).

**Increment 4 — queued agent-task system + `Tasks.sq` migration (the headline):**
- **Schema v7 → v8 (real SQLDelight migration, invariant #20 dance EXACTLY):** (a) generated `7.db` (current schema) via `generateCommonMainMobileAgentDatabaseSchema` BEFORE any change; (b) wrote `7.sqm` (`CREATE TABLE tasks` + index, new table, no ALTERs); (c) wrote `Tasks.sq` with a byte-identical CREATE so `Schema.create` (fresh v8) and `Schema.migrate` (v7→v8) converge; (d) `verifyCommonMainMobileAgentDatabaseMigration` PASSED. `DesktopDatabaseFactory` reads `schema.version` dynamically, so it auto-migrates — confirmed live: `[DB] migrated schema 7 → 8`. `tasks` columns: `id, prompt, status (TEXT enum), progress (REAL), created_at_epoch_ms, updated_at_epoch_ms, result, error, attachments (JSON TEXT)` — new (nullable) columns at the END.
- **`commonMain` model + queue** (so Android can later reuse): `QueuedTask` + `TaskStatus{QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELLED}`; `TaskRepository` interface + `SqlDelightTaskRepository` (mirrors `SqlDelightTodoRepository` — synchronously-seeded `StateFlow`, mutations on IO, attachments JSON round-trip); `TaskRunner` fun-interface (the seam to the warm `AgentLoop` — keeps `TaskQueue` free of `InferenceSession` coupling + unit-testable); `TaskQueue` — single-consumer coroutine: re-queues orphaned RUNNING rows on `start` (crash recovery), pulls the oldest QUEUED via a CONFLATED wakeup channel, runs sequentially through one `TaskRunner`, records terminal state + fires a `NotificationPresenter` completion, and distinguishes per-task cancel from scope shutdown via `scope.isActive`.
- **Wired in `desktopModule`:** `tasksQueries` handle + `TaskRepository`. The `TaskQueue`/`TaskRunner` binding lands with the system-tray increment (needs the warm session + app scope).
- **Tests (runtime verification of the headline — the tray UI itself is the operator's check):** `TaskQueueTest` (fake runner + in-memory repo) — sequential single-consumer execution (asserts max-concurrency 1), failure doesn't stop the queue, cancel-of-running interrupts + continues, cancel-of-queued skips; `SqlDelightTaskRepositoryTest` (real in-memory DB) — lifecycle transitions, oldest-first `nextQueued`, attachments JSON round-trip, `deleteFinished` terminal-only, failed-finish preserves error. Both green in `:shared:desktopTest`.
- **Verified:** all four desktop gates (DI_CHECK exercised the live v7→v8 migration) + `:shared:desktopTest` (new task tests green) + **`:androidApp:assembleDebug`** (v8 schema + `verifyMigrations`) green.

**Increment 5 — system tray + warm-model background (`:desktopApp`):**
- **Warm model (`WarmModel`):** holds the GGUF `ModelHandle` resident for the app lifetime (no Android foreground-service / watchdog constraint, #1/#29). Loads lazily on first task (via `DesktopModelInventory.localFile()`, `GEMMA_GGUF_PATH` dev override), survives the window being hidden; in-flight `generate()` keeps running on the long-lived app scope.
- **`DesktopTaskRunner` (the `TaskRunner` impl):** runs a `QueuedTask` through `AgentLoopFactory.create(warmModel.session(), language, NoOp)` + `AgentLoop.run`, collecting the streamed turn into the final assistant text, coarse progress (GenerationStarted→first-token→done), rethrowing `AgentEvent.Error` so the queue records FAILED.
- **Tray + hide-to-tray (`Main.kt`):** Compose Desktop `Tray` (procedural brand-blue icon) with **Show / Pause queue (CheckboxItem → `TaskQueue.stop`/`start`) / Quit**; window-close sets `visible=false` (hide to tray) instead of quitting — the tray keeps the app alive and the warm model resident. Quit unloads the model + cancels the app scope. A minimal `QueueStatusScreen` renders live queue depth + running-task progress from `TaskQueue.tasks`.
- **Unified notifications:** `MutableNotificationPresenter` (`:shared` desktopMain) bound as the single `NotificationPresenter` (concrete + interface) — starts logging, and once the tray composes the app calls `setDelegate(TrayNotificationPresenter(trayState))`, so BOTH clock fires and task completions surface as `TrayState.sendNotification` toasts (no Koin-override gymnastics, no chicken-and-egg with `TrayState`).
- **Startup lifecycle (the carried TODOs, now done):** `Main` calls `ClockService.rearmAll()` (re-arm persisted alarms/timers — the desktop boot-receiver analogue), `DesktopTelemetryScheduler.start()` (24 h upload loop), and `TaskQueue.start()`.
- **`:desktopApp` deps:** added `compose.foundation` + `compose.material3` + `kotlinx-coroutines-core`.
- **Verified:** `:shared:compileKotlinDesktop` + `:desktopApp:compileKotlin` + `:desktopHarness:compileKotlin` + `DI_CHECK=1 :desktopApp:run` (now `opened schema v8`, force-resolves Clock/Task/Telemetry/Notification singletons) + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green. Actual tray rendering / a live background turn = the operator's manual check (no display in CI).

**Increment 6 — model download via tray (`:desktopApp`):**
- **`DesktopModelDownloadController`:** drives the Phase-4 `DesktopModelDownloader` from the app, replacing Android's WorkManager `ModelDownloadWorker`. Exposes a `StateFlow<ModelDownloadStatus>` (`Idle`/`Present`/`NotConfigured`/`Downloading(fraction)`/`Failed`); coarse milestones (start / complete / fail) fire tray notifications through the shared `NotificationPresenter`. `ensurePresent()` is a no-op when the model is already downloaded and reports `NotConfigured` (no fetch) when the spec is blank — so a fresh checkout / CI neither downloads nor errors. Per-percent throttling on the StateFlow so the UI doesn't churn per 64 KB chunk.
- **Wired in `Main`:** constructed on the app scope, `ensurePresent()` called at startup alongside the other lifecycle starts (background fetch with tray progress). `QueueStatusScreen` now shows a model-status line (`ready` / `downloading N%` / `not configured` / failure) above the queue.
- **Verified:** `:desktopApp:compileKotlin` + `DI_CHECK=1 :desktopApp:run` + **`:androidApp:assembleDebug`** green (`:desktopApp`-only change). A real download is the operator's check (the bundled spec is intentionally blank — `NotConfigured` — until they fill it).

**Increment 7 — Markdown/LaTeX `expect`/`actual` (`:ui`):**
- **New deps:** catalog `markdownRenderer = "0.41.0"` (`com.mikepenz:multiplatform-markdown-renderer-m3`) + `jlatexmath = "1.0.7"` (`org.scilab.forge:jlatexmath`), added to `:ui` `desktopMain`; Markwon (`core`/`ext-latex`/`inline-parser`) added to `:ui` `androidMain`.
- **`:ui` commonMain:** `LatexNormalizer` (moved/copied from `:androidApp` — pure Kotlin, the math-vs-currency `$…$`→`$$…$$` heuristic, invariant #41) + `MarkdownMath(text, renderMarkdown, modifier)`. The **gate is common**: `renderMarkdown=false` → plain `bodyMedium` `Text`; `true` → the `expect fun PlatformMarkdownMath`.
- **Android actual:** Markwon + `ext-latex` (jlatexmath, native canvas, no WebView) in an `AndroidView` TextView mirroring `bodyMedium` metrics — the same stack as `:androidApp`'s `MarkdownMathText`.
- **Desktop actual:** the Compose-MP markdown renderer (`com.mikepenz.markdown.m3.Markdown`, no WebView) + LaTeX blocks rendered to `ImageBitmap` via JLaTeXMath (`TeXFormula`→`TeXIcon`→`BufferedImage.toComposeImageBitmap`, `renderLatexToImageBitmap`). Text is normalized then split on `$$…$$`: markdown runs render via `Markdown(...)`, math blocks as images (falling back to inline-code if a formula won't parse); a former-inline `$…$` becomes its own stacked block (correct + readable; pixel-perfect inline is a later refinement).
- **Cardinal-rule note:** `:androidApp`'s own `MarkdownMathText` + `LatexNormalizer` are untouched (the live Chat still uses them); the `:ui` versions stand beside them until the Chat screen moves to `:ui` in Phase 9, which then deletes the `:androidApp` copies.
- **Verified:** `:ui:compileKotlinDesktop` + `:ui:compileAndroidMain` (both new actuals + all three new libs resolve) + `:desktopApp:compileKotlin` + `DI_CHECK=1 :desktopApp:run` + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green. Actual rendering = the operator's visual check (no display in CI).

**Increment 8 — voice I/O (`ChatSpeaker` + extracted `Dictation`):**
- **Seam interfaces (`:shared` commonMain `voice/`):** `ChatSpeaker` (speak/stop/`isSpeaking` StateFlow) + a NEW `Dictation` (`results: Flow<String>` / `isListening` / start/stop/destroy). Promoted to `commonMain` so the shared Chat ViewModel (Phase 9) drives either platform; `:androidApp` keeps its own `ChatSpeaker`/`SpeechDictation` under `app.ui.chat` until then (no churn, invariant #42).
- **`DesktopTtsSpeaker` (`:shared` desktopMain):** read-aloud via per-OS `ProcessBuilder` shell-out — `say` (macOS) / `spd-say --wait` (Linux) / PowerShell `SpeechSynthesizer` (Windows), no-op + log when the engine is absent. `isSpeaking` flips around the spawned process for echo suppression (#42); `speak` flushes the prior utterance (`QUEUE_FLUSH` parity), `stop` kills the process.
- **`VoskDictation` (`:shared` desktopMain):** offline STT via Vosk JNI (`com.alphacephei:vosk:0.3.45`, catalog + desktopMain dep) — captures 16 kHz mono PCM (`javax.sound.sampled`) into a Vosk `Recognizer`, emits each completed utterance's text on `results`. **Degrades to no-op** when the acoustic model is absent (`MOBILEAGENT_VOSK_MODEL` env or `<app-data>/models/vosk`) or no mic line exists — the ONNX/GGUF missing-artifact pattern, CI-safe.
- **Wired in `desktopModule`:** `ChatSpeaker`→`DesktopTtsSpeaker`, `Dictation`→`VoskDictation`.
- **Verified:** `:shared:compileKotlinDesktop` + `:desktopHarness:compileKotlin` + `DI_CHECK=1 :desktopApp:run` + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green (Vosk resolves). A real voice round-trip = the operator's check (no mic / TTS engine / Vosk model in CI).

**Increment 9 — vision image pipeline (`ImagePreprocessor` + `FilePicker`):**
- **Seam interfaces (`:shared` commonMain `vision/`):** `FilePicker.pickImage(): ByteArray?` (raw bytes of the chosen file) → `ImagePreprocessor.toModelJpeg(bytes): ByteArray?` (decode + downscale longest edge to 768 px + JPEG re-encode). Both `ByteArray`-based — no platform image type — so they sit in `commonMain` (invariant #39); the Android actuals land in Phase 9.
- **Desktop actuals (`:shared` desktopMain):** `DesktopImagePreprocessor` (`javax.imageio.ImageIO` decode → bilinear scale onto opaque RGB → JPEG via `ImageWriter` at q0.85; EXIF auto-orientation not applied by ImageIO — noted, deferred); `DesktopFilePicker` (Swing `JFileChooser` on the EDT via `invokeAndWait`, image-extension filter, reads bytes off the EDT).
- **Engine vision stays OFF (text-only).** Per the RISK register / #39, llama.cpp Gemma `mmproj` maturity is uncertain, so `WarmModel` keeps `enableVision=false`; the pipeline is wired (the Phase-9 Chat UI can capture + downscale images) but the bytes only reach the model once vision is validated — desktop ships text-only until then.
- **Wired in `desktopModule`:** `FilePicker`→`DesktopFilePicker`, `ImagePreprocessor`→`DesktopImagePreprocessor`.
- **Fix during impl:** `ImageWriteParam.canWriteCompressed` is a protected *field*; called the public `canWriteCompressed()` method instead.
- **Verified:** `:shared:compileKotlinDesktop` + `:desktopHarness:compileKotlin` + `DI_CHECK=1 :desktopApp:run` + `:shared:desktopTest` + **`:androidApp:assembleDebug`** green. Real image decode / vision inference = the operator's check (no display / vision model in CI).

**Phase 7 COMPLETE.** All nine increments landed + verified green on `feature/desktop-cmp`. Next: Phase 8 (packaging / GPU natives), Phase 9 (UI cutover screen-by-screen, Chat last — consolidates the `:androidApp` MarkdownMathText/LatexNormalizer/ChatSpeaker copies into the `:ui`/`:shared` seams this phase introduced).

**Post-Phase-7 fix — aux engines never warmed on desktop (operator-reported).** With correct `.onnx` artifacts present, the desktop classifier + embedder still logged `classifier unavailable` / `embedder unavailable`: `OnnxClassifierEngine.classify()` / `OnnxEmbedderEngine.embed()` return `null` while their `OrtSession` is unset and do **not** lazily warm — `session` is only set by an explicit `warmUp()`. Android drives that from the Chat-screen `LifecycleResumeEffect` (`MainViewModel.warmUpAuxEngines`, invariant #22); the cross-platform `AppNavHost.chatWarmUp` slot that carries it was passed empty on desktop, and `WarmModel` warms only the GGUF LLM — so nothing ever called `warmUp()` and both engines stayed inert (silent degrade to Gemma-only + no-op memory). **Fix:** `Main.kt` eagerly warms both engines once on the long-lived `appScope` at startup, alongside `rearmAll()` / telemetry / `taskQueue.start()` / `modelDownload.ensurePresent()` (`warmUp()` is idempotent + non-throwing; the sessions stay resident for the JVM lifetime — the desktop "keep everything warm" model, and it also covers the launched-to-tray / `TaskQueue`-only path where Chat is never shown). The visibility-keyed `chatWarmUp` seam was considered but left empty: desktop has no Android `Lifecycle`, the window opens straight to Chat, and there's no LMKD memory pressure to gate on (invariant #4), so startup warm-up is the better fit. Verified: `:desktopApp:compileKotlin` green.

---

## Phase 8 — Status: IN PROGRESS (branch `feature/desktop-cmp`)

Packaging / distribution. Increment order (lowest-risk build-graph/config first; the CI matrix + native bundles are the headline; the custom-`libjllama`/GPU-probe is the riskiest and is scoped/deferred):

1. ✅ jpackage metadata + real icons + JVM/module config · 2. CI matrix workflow (ubuntu/macOS/Windows) · 3. native-runtime strategy doc + GPU/CUDA-EP optional swaps · 4. (optional) first-run polish (single-instance, permissions).

**Host-toolchain boundary (this Linux x86-64 CI box):** `jpackage` + `dpkg-deb` are present; `rpmbuild` and **`fakeroot` are NOT** (no install candidate / no root), and there's no display. So `:desktopApp:createDistributable` (jpackage **app-image** — runtime + launcher + icon, no system packaging tool) runs here and is the desktop gate; the final `.deb`/`.rpm`/`.dmg`/`.msi` wrap + a real installed-app launch are the operator's checks on real hardware / the CI matrix — same boundary as the Phase 4/5/7 runtime items.

**Increment 1 — jpackage metadata + real icons + JVM config (commit `aa1e936`, `:desktopApp`-only):**
- **`nativeDistributions`:** `vendor`/`description`/`copyright`; all six installer formats declared (`Deb`/`Rpm`, `Dmg`/`Pkg`, `Msi`/`Exe`) — jpackage builds **per host OS**, so the inc-2 CI matrix produces one set per runner. Per-OS blocks: Linux (`debMaintainer`, `menuGroup`/`appCategory` = Utility, `rpmLicenseType`, lowercase `mobile-agent` package name), macOS (`bundleID` `com.contextsolutions.mobileagent`, `public.app-category.productivity`), Windows (**stable `upgradeUuid`** `b6c3f2a4-…-4f33` — never regenerate once shipped, MSI uses it for upgrade-vs-fresh; `perUserInstall`, `dirChooser`, `shortcut`).
- **`includeAllModules = true`:** ships the full JDK runtime image rather than a hand-curated jlink module set. The app pulls in modules easy to miss when probing (`java.sql` for the SQLite JDBC driver, `jdk.management` for the OS-MXBean headroom probe #Phase-7-inc-1, `java.desktop` for the Swing file chooser + `javax.sound` capture, `jdk.crypto.ec` for TLS to Brave); the extra runtime weight is trivial next to the multi-GB model downloaded at first run, and it keeps the "CPU-default build that ALWAYS works" guarantee.
- **`jvmArgs`:** `-Xmx2g` (the GGUF model + ONNX run in **native off-heap** memory via llama.cpp / ONNX Runtime, so the JVM heap stays modest — 2 GB covers Compose UI + image preprocessing + ONNX marshalling) + `-Dfile.encoding=UTF-8`.
- **Icons:** `desktopApp/icons/generate_icons.py` (Pillow) renders one brand-blue rounded-square + chat-bubble master → `icon.png` (512, Linux installer + the in-app tray/window resource at `src/main/resources/icon.png`), `icon.ico` (7 sizes, Windows), `icon.icns` (macOS). All three committed so the build needs **no image toolchain**; the script is the regeneration source of truth. `Main.kt` replaces the procedural `Painter` square with the bundled `icon.png` as the tray + window icon — **lazy** (`by lazy`), because `decodeToImageBitmap` pulls in Skia's display-bound natives (`libGL`), which would crash the headless `DI_CHECK` early-exit if decoded at class-init.
- **Verified on this host:** `:desktopApp:createDistributable` produces a complete app-image — `bin/MobileAgent` launcher, bundled `lib/MobileAgent.png`, the `llama`/`onnxruntime`/`vosk`/`sqlite-jdbc` native-bearing jars on the classpath, a 50-entry runtime image, and `-Xmx2g` / `-Dfile.encoding=UTF-8` in `MobileAgent.cfg`. `:desktopApp:packageDeb` reaches the DEB bundler and stops **only** on missing `fakeroot` (the `.deb` wrap = operator/CI). `:desktopApp:compileKotlin` + `DI_CHECK=1 :desktopApp:run` (`opened schema v8`, "resolved OK") + **`:androidApp:assembleDebug`** (cardinal) all green.

**Increment 2 — CI matrix for native installers (commit `2efd1e9`, CI-only):** `.github/workflows/desktop-package.yml` — a 3-runner matrix (`ubuntu`→deb+rpm, `macos`→dmg+pkg, `windows`→msi+exe) since jpackage builds per host OS. Runs `:desktopApp:packageDistributionForCurrentOS` (the **non-minified** variant — the release/ProGuard path is risky for the Koin/sqlite-jdbc/llama/ONNX/Vosk reflection+JNI deps; "CPU-default build that ALWAYS works" first) and uploads each OS's installers. Triggered on `workflow_dispatch` + `v*` tags (packaging is slow + large; the per-commit gate stays the local desktop tasks). One `shell: bash` + working-dir covers all three OSes (Git Bash runs the checked-in `./gradlew` on Windows); Temurin 17 bundles jpackage; the Linux job `apt-get install -y fakeroot rpm` (the bundler shell-outs). **Verified:** YAML parses; structure asserted (3 OSes, 6 steps). The actual matrix run is the operator's check (no macOS/Windows runners here).

**Increment 3 — native-runtime strategy doc + ONNX GPU swap (commit `75255a0`):** `docs/DESKTOP_PACKAGING.md` — the packaging/native-runtime reference (jpackage-per-OS, host-toolchain table, `includeAllModules`/heap rationale, the three native runtimes' CPU-default + GPU-opt-in story, bundled-config vs downloaded-model split + per-OS app-data dirs, a GPU-driver matrix, and the deferred list). **ONNX CUDA-EP is now a real build option:** `-PonnxGpu=true` swaps `:shared` desktopMain/desktopTest from `onnxruntime` (CPU, default) to `onnxruntime_gpu` (catalog `onnxruntime-gpu`, same 1.20.0) via a single `onnxRuntimeDep` val; the Onnx engines already try the CUDA EP + fall back to CPU, so the GPU jar is a strict superset. **Verified:** default `:shared:compileKotlinDesktop` green; `-PonnxGpu=true` resolves `onnxruntime_gpu:1.20.0`; `:shared:desktopTest` + **`:androidApp:assembleDebug`** (cardinal) green.

**Increment 4 — single-instance lock (commit `11116f9`, `:desktopApp`-only):** a second instance would mean two warm models, two `TaskQueue` consumers racing the same `tasks` rows, and SQLite write contention. `Main.kt` acquires an exclusive OS `FileLock` on `<app-data>/.instance.lock` (`DesktopAppDirs.dataDir()`) held for the JVM lifetime (OS releases on exit, crashes included); a second process sees `tryLock() == null` → "already running" → exits. Checked **after** the `DI_CHECK` early-exit (the smoke test never contends), and **fails open** (any non-contention error logs + proceeds). **Verified:** `:desktopApp:compileKotlin` + `DI_CHECK` (lock not reached) + `createDistributable` green; a standalone two-process `FileLock` probe confirmed the second acquirer gets `null`. `:desktopApp` is not on the Android build path (cardinal unaffected, last green at inc 3).

**Phase 8 COMPLETE.** All four increments landed + verified green on `feature/desktop-cmp`. The native bundles (mac/win + the Linux `.deb`/`.rpm` wrap, which needs the absent `fakeroot`/`rpmbuild`) and a real installed-app launch + GPU offload are the operator's checks on real hardware / the CI matrix — same boundary as the Phase 4/5/7 runtime items. **Next: Phase 9** (UI cutover, screen-by-screen, Chat last — consolidates the `:androidApp` MarkdownMathText/LatexNormalizer/ChatSpeaker copies into the `:ui`/`:shared` seams).

---

## Phase 9 — Status: IN PROGRESS (branch `feature/desktop-cmp`)

UI cutover, screen-by-screen, simplest first, **Chat LAST**, with `:androidApp:assembleDebug` green at every commit.

**Dependency-driven ordering (refined from the plan sketch):**
1. ✅ **UI-module foundations** (this increment) · 2. Todo (proof) · 3. Settings · 4. Memory · 5. History · 6. Download · 7. Onboarding · 8. Clock (Alarm/Timer) · 9. **Chat** (last — MarkdownMath + vision/voice seam actuals + the nav host `when` moves with it) · 10. shell shrink + release prep (version bump → v0.1.0 tag at merge).

**Two findings that shaped the order:**
- The nav host `when` (`MainScreen`) references *every* screen (`ChatScreen`/`SettingsScreen`/…), which live in `:androidApp`. `:ui` cannot depend on `:androidApp`, so the **host moves LAST with Chat**; only the route *model* + the back-handler *seam* can move early.
- **`ResourceLoader` (the plan's deferred-from-Phase-6 item) is NOT needed by any moving screen** — all asset access already lives in androidMain engines / `AndroidKoinModule` / desktopMain (`DesktopResources`), handled in Phase 6. No screen or its ViewModel reads assets directly. **Deferred as unnecessary for v0.1.0** rather than building speculative cross-platform infra; revisit only if a later increment surfaces a direct-asset read in shared UI.

**Increment 1 — UI-module foundations:** pure plumbing, no screen logic moved.
- **`:ui` build:** commonMain gains `compose.materialIconsExtended` (the migrated screens' icons) + `koin-core` + `koin-compose-viewmodel` (the latter transitively pulls the JetBrains multiplatform `lifecycle-viewmodel` artifact, so `ViewModel`/`viewModelScope`/`koinViewModel()` resolve in commonMain). androidMain gains `activity-compose` for the `BackHandler` actual.
- **`uiModule`** (`:ui` commonMain `ui/di/UiModule.kt`) — the shared Koin module for migrated screens' ViewModels. Starts **empty**; each screen adds its `viewModelOf(::X)` as it moves. Loaded by BOTH shells: `MobileAgentApplication.startKoin(agentCoreModule, androidModule, uiModule)` + desktop `Main.startKoin(agentCoreModule, desktopModule, uiModule)`.
- **`MainRoute` + `Saver`** moved verbatim from `:androidApp`'s `MainScreen.kt` to `:ui` commonMain (`ui/navigation/MainRoute.kt`, now `public`). The host `when` stays in `:androidApp`'s `MainScreen` and imports the moved type.
- **`PlatformBackHandler`** (`ui/navigation/`) — `expect fun PlatformBackHandler(enabled = true, onBack)`; Android actual wraps activity-compose `BackHandler`, desktop actual is a no-op (explicit on-screen back buttons; window close = OS back). `MainScreen`'s four `BackHandler {…}` callsites switched to `PlatformBackHandler {…}`.
- **`:androidApp` now depends on `:ui`** (was `:shared`-only) so the Android shell renders migrated screens from `:ui`.
- **Drive-by fix:** `MemoriesMigrationTest.schema_version_reflects_all_sqm_migrations` asserted schema v7 but Phase 7 inc 4 added `7.sqm` (tasks) → bumped to v8; the assertion + comment were stale (failing since `df24827`). Corrected to 8.
- **Verified:** `:ui:compileKotlinDesktop` + `:ui:compileAndroidMain` + `:shared:compileKotlinDesktop` + `DI_CHECK=1 :desktopApp:run` ("resolved OK", `opened schema v8`) + **`:androidApp:assembleDebug`** + `:shared:desktopTest` + `:androidApp:testDebugUnitTest` all green.

**Increment 2 — Todo screen → `:ui` (the migration proof):**
- `TodoViewModel` + `TodoManagementScreen` moved **verbatim** (only the package line changed: `app.ui.todo` → `ui.todo`) from `:androidApp` into `:ui` commonMain (`git mv`, history preserved). Both were already free of Android types — `TodoViewModel` uses only the multiplatform `androidx.lifecycle.ViewModel`/`viewModelScope` + the shared `TodoRepository`; the screen uses only CMP Compose + `material-icons-extended` + `kotlinx-datetime` + the shared `Todo`/`TodoPriority` types. This is why Todo is the first/proof screen.
- **DI:** `viewModelOf(::TodoViewModel)` moved from `androidModule` to `uiModule` (its one dep, `TodoRepository`, was already Koin-owned and is bound by both platform modules). Resolved on **both** shells now (`DI_CHECK` exercises the desktop side).
- **Callsites:** the two importers (`MainScreen` → `TodoManagementScreen`, `ChatScreen` → `TodoViewModel` for the header badge) re-pointed to `com.contextsolutions.mobileagent.ui.todo`. `koinViewModel()` callsites unchanged (resolution is package-agnostic).
- **`:ui` build:** added `kotlinx-datetime` to commonMain (`:shared` keeps it `implementation`, so it isn't exposed transitively) and a `configurations.all { resolutionStrategy { force(libs.kotlinx.datetime) } }` pin to **0.6.1** — CMP material3 transitively upgrades it to 0.7.x, which relocated `kotlinx.datetime.Clock` and would break the screen's `Clock.System`/`Instant` calls (same pin `:desktopApp` already carries).
- **Verified:** `:ui:compileKotlinDesktop` + `:ui:compileAndroidMain` (the moved screen compiles in commonMain for both targets) + `DI_CHECK=1 :desktopApp:run` + **`:androidApp:assembleDebug`** + `:androidApp:testDebugUnitTest` + `:shared:desktopTest` all green.

**Increment 3 — Settings cluster → `:ui` (first seam work):** `SettingsScreen`/`SettingsViewModel` + `SearchSourcesScreen`/`SearchSourcesViewModel` moved to `:ui` commonMain (`ui.settings`). `SearchSources` was already clean; `Settings` needed two decouplings:
- **`AppBuildConfig` seam (NEW commonMain interface, `platform/`):** `isDebug`/`isInternalBuild`/`hasBraveDevKey`/`hasHfDevToken`. `SettingsViewModel` referenced `:androidApp`'s generated `BuildConfig` (dev keys + DEBUG), invisible from `:ui`. Android actual `AndroidAppBuildConfig` (in `:androidApp`, reads `BuildConfig`, bound in `androidModule`); desktop `DesktopAppBuildConfig` (constant — no bundled dev secrets; `isDebug` from `-Dmobileagent.debug`, bound in `desktopModule`). The VM exposes `isDebugBuild` through `SettingsUiState` so the screen's `BuildConfig.DEBUG` gate became `state.isDebugBuild`.
- **`Context` dropped:** the only `Context` use was the debug `triggerTelemetryUploadNow()` → `TelemetryUploadWorker.runNow(ctx)`. Replaced with the commonMain `TelemetryUploader` (already bound on both shells) called on `viewModelScope`.
- **Cross-VM decoupling:** `SettingsScreen` pulled `MemoryViewModel` only for a count + creation-toggle summary label. Rather than drag the heavy Memory backup work into this increment, `SettingsViewModel` now reads `MemoryStore.listAll().size` + `MemoryPreferences.creationEnabled()` itself (`refreshMemorySummary()`), exposing `memoryCount`/`memoryCreationEnabled` on its state; the `MemoryViewModel` param is gone.
- **DI:** both VMs → `uiModule` (`viewModelOf`); removed from `androidModule` (including the explicit `viewModel { SettingsViewModel(appContext=…) }`). `AppBuildConfig` bound in both platform modules.
- **Test:** `SearchSourcesViewModelTest` (stays in `:androidApp`) gained an explicit import for the moved VM (was same-package).
- **Verified:** all 6 gates green (`:ui` ×2, `DI_CHECK`, `assembleDebug`, `testDebugUnitTest`, `desktopTest`).

**Increment 4 — History → `:ui`:** `ConversationHistoryScreen`/`ConversationHistoryViewModel` → `ui.history`. VM was already clean. The screen's only Android coupling was `android.text.format.DateUtils` for a relative "updated" label — replaced by a NEW pure-Kotlin **`ui/util/Formatting.kt`** (`formatRelativeTime(epochMs, nowMs)` mirroring `FORMAT_ABBREV_RELATIVE`, plus `formatFileSize` for the upcoming Download increment), kotlinx-datetime-based, identical on both platforms. `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`. VM binding → `uiModule`; MainScreen import re-pointed. All 5 gates green.

**Download — DEFERRED (kept Android-specific):** `DownloadScreen`/`DownloadViewModel` depend on `:androidApp`'s `app.service` model-download stack (`ModelDownloadController`/`DownloadState`/WorkManager). Desktop has an entirely separate download subsystem (`DesktopModelDownloadController` + the tray/`QueueStatusScreen`, built Phase 7 inc 6) with different state and UX (no metered toggle). Unifying both behind one shared screen is a large, risky refactor with no payoff — desktop already has its download surface. The Android `DownloadScreen` stays in `:androidApp` (part of the Android-only WorkManager download subsystem), like the other Android-only services.

**Increment 5 — Clock → `:ui`:** `AlarmManagementScreen` + `TimerManagementScreen` + `ClockViewModel` → `ui.clock`. All three were already clean (only CMP Compose + shared clock types; `TimePicker` etc. are CMP commonMain). VM binding → `uiModule`; MainScreen + **ChatScreen** imports re-pointed (ChatScreen references `ClockViewModel` for the header — a reminder to grep *all* `:androidApp` referrers, not just MainScreen/DI/tests). Drive-by: dropped the unused `formatFileSize` from `ui/util/Formatting.kt` (Download isn't moving). All 5 gates green.

**Increment 6 — Onboarding → `:ui`:** all 7 files (`OnboardingViewModel`, `OnboardingHost`, `DisclosureScreen`, `BraveKeyScreen`, `HfAuthTokenScreen`, `LocationPickerScreen`, `TelemetryConsentScreen`) → `ui.onboarding`. Two seams:
- **`UrlOpener` (NEW commonMain interface, `platform/`):** `BraveKeyScreen`/`HfAuthTokenScreen` opened dashboard URLs via `Intent(ACTION_VIEW)`. Android actual `AndroidUrlOpener` (Context + `FLAG_ACTIVITY_NEW_TASK`, bound `androidModule` via `androidContext()`); desktop `DesktopUrlOpener` (`java.awt.Desktop.browse`, headless-guarded, bound `desktopModule`). Screens use `koinInject<UrlOpener>()`.
- **`LocaleProvider.countryCode()` (NEW method on the existing expect class):** `LocationPickerScreen.detectDeviceCountry(context)` read `context.resources.configuration.locales`. Added `countryCode()` to the `LocaleProvider` expect + all 3 actuals (android/desktop = `java.util.Locale.getDefault().country`; iOS = `NSLocale.currentLocale.countryCode`). Screen uses `koinInject<LocaleProvider>().countryCode()`. (`LocaleProvider` already bound in `agentCoreModule`.)
- DI: `OnboardingViewModel` → `uiModule`; `UrlOpener` bound in both platform modules; MainScreen import re-pointed. All 6 gates green.

**Increment 7 — Memory cluster → `:ui` (heaviest seam work):** `MemoryViewModel` + `MemoryScreen` + `ConversationMemoryListScreen` → `ui.memory`. Four pieces:
- **Backup file-picker seam (the hard part).** `MemoryScreen` used SAF launchers (`CreateDocument`/`OpenDocument`) → `Uri`; `MemoryBackupController` (in `:androidApp`) consumed `Uri` via `contentResolver`. Refactored: `MemoryBackupController` + `MemoryBackupOps` moved to **`:shared` commonMain** operating on new commonMain `BackupWriter`/`BackupReader` (UTF-8 text handles); its `Context`/`Uri`/`Log`/`BuildConfig` dependencies became an injected `appVersionName` (from `AppBuildConfig.versionName`, NEW field) + a `logger` lambda. The picking itself is a `:ui` `@Composable expect fun rememberBackupFilePicker(): BackupFilePicker` — Android actual registers the SAF launchers in composition and wraps the `Uri` as `BackupWriter`/`BackupReader` over `contentResolver`; desktop actual uses a Swing `JFileChooser` wrapping a `java.io.File`.
- **`Toaster` seam (NEW commonMain interface):** `MemoryScreen`'s `Toast` calls → `koinInject<Toaster>().show(…)`. Android = `Toast` (main-looper-posting); desktop = stderr log (a richer surface can swap in later). Bound in both platform modules.
- **`DateUtils` / `java.time` / `System.currentTimeMillis()`** → `ui.util.formatRelativeTime` + kotlinx-datetime (`createdLabel`, `relativeCreatedLabel`, `defaultExportFilename`).
- **`MemoryViewModel`** `onExport(Uri)`/`onImport(Uri)` → `onExport(BackupWriter)`/`onImport(BackupReader)`; its test-only `internal var ioDispatcher` became a constructor param with a default (cross-module test access; Koin's `viewModelOf` honours the default). `MemoryViewModelTest` updated (moved-package imports, `Uri` mocks → `BackupWriter`/`BackupReader`).
- DI: `MemoryBackupOps` rebound in **both** platform modules (desktop gains memory backup); `MemoryViewModel` → `uiModule`; `Toaster`/`UrlOpener`/`AppBuildConfig` all in both. MainScreen imports re-pointed. All 6 gates green (incl. the updated unit test).

**Increment 8 — Chat (LAST, in sub-increments; user-confirmed "proceed full Chat now").** `ChatScreen` (~900 lines) + `ChatViewModel` are the highest cardinal-rule risk + need several new seams the brief didn't anticipate. Done as verified sub-increments, Android green at each commit:
- **8a (64d7f05) — session lifecycle abstraction.** `SessionState` → `:shared` commonMain (`inference/`). NEW commonMain `ChatSessionController` (`agent/`): `state: StateFlow<SessionState>` + `suspend newSession(): InferenceSession`. Android actual `AndroidChatSessionController` wraps `InferenceSessionManager` + `ModelInventory` + `InferenceSessionAdapter` (bound androidModule). `ChatViewModel` ctor `sessionManager`+`inventory` → `sessionController`. 5 tests updated.
- **8b (3bd0acd) — voice seam consolidation.** NEW commonMain `voice.TtsPreferences`. `AndroidTtsSpeaker` + `SharedPreferencesTtsPreferences` git-mv'd to `:shared` androidMain `voice/` as actuals of `voice.ChatSpeaker`/`voice.TtsPreferences` (duplicate `app.ui.chat` interfaces deleted). NEW desktop `DesktopTtsPreferences` (JSON). `ChatViewModel` now imports the `voice.*` seams.
- **8c (`4935d85`) — vision seam.** `ChatViewModel` off `Context`/`Uri`/`ImageBitmap`: `onImagePicked(Uri)`→`onImagePicked(ByteArray)`, `ChatUiState.pendingImageThumbnail: ImageBitmap?`→`pendingImageBytes: ByteArray?`, `UiMessage.User` dropped `thumbnail` (kept `imageBytes` for live + reloaded), `appContext` ctor param gone. NEW `:ui` commonMain `ui/chat/ImagePicker.kt` (`interface ImagePicker { launch(onPicked: (ByteArray?) -> Unit) }` + `@Composable expect rememberImagePicker()`): Android actual `PickVisualMedia`→read bytes via `contentResolver`→`koinInject<ImagePreprocessor>().toModelJpeg` off the main thread; desktop actual `koinInject<FilePicker>().pickImage()`+`toModelJpeg` on a coroutine. NEW `:ui` `ui/util/ImageDecode.kt` `expect decodeImageBitmap(ByteArray): ImageBitmap?` (Android `BitmapFactory`; desktop `Skia.Image.makeFromEncoded`) — used by `UserBubble` + the staged-image chip. NEW `:shared` androidMain `vision/AndroidImagePreprocessor` (`ImageDecoder` + `ByteBuffer.wrap` source, EXIF-aware), bound in androidModule; deleted the dead `app.ui.chat.ImagePreprocessor`. `ChatScreen` switched to `rememberImagePicker()` + `decodeImageBitmap`. Chat VM tests dropped the `appContext` arg. All 7 gates green.
- **8d (`fa06d20`) — the move.** `ChatViewModel` `Log`→injected `ChatLogger` (`:shared` commonMain fun-interface, keeps the `"ChatViewModel"` tag #28, bound per shell), `System.currentTimeMillis()`→`kotlinx.datetime.Clock`; `ChatViewModel` + `ChatScreen` + `AckPhrasePicker`/`MarkdownToPlainText`/`MemoryPromptCard`/`ThermalBanner`/`VoiceCommand` git-mv'd to `:ui` `ui/chat`. `SpeechDictation` rewritten as `:shared` androidMain `AndroidDictation` (`results: Flow`) — the Android actual of `voice.Dictation` (desktop `VoskDictation`); bound androidModule. `MarkdownMathText`→`:ui` `MarkdownMath`; deleted `:androidApp` `MarkdownMathText`/`LatexNormalizer`. NEW seams to shed the remaining Android couplings: **`MicPermission`** (`:ui` expect/actual — RECORD_AUDIO + recognition-available; desktop always-granted), **`AccessibilityAnnouncer`** (`:ui` expect/actual — `View.announceForAccessibility` / desktop no-op, #26), **`SystemMemoryStatusProvider`** (`:shared` — Android wraps `SystemMemoryMonitor`, desktop constant Green; replaces the deleted `SystemMemoryStatusViewModel`), `UrlOpener` for citation taps + `ui.util.urlHost` (replaces `Uri.host`), and `AppBuildConfig` gained `versionCode`/`gitDescribe` for the About dialog. **Theme:** `ThemeMode` enum + `ThemePreferences` → `:shared` commonMain (below `:ui`, like every prefs interface), `ThemeModeViewModel` → `:ui`; Android `SharedPreferencesThemePreferences` stays `:androidApp`, new desktop `DesktopThemePreferences` (JSON). **Nav host:** `AppNavHost` (`:ui` `ui/navigation`) hosts the route `when`; Android `MainScreen` is now a thin wrapper supplying gating (`MainViewModel`) + the `LifecycleResumeEffect` aux warm-up (#22) + the WorkManager `DownloadScreen` slot. **DI:** `uiModule` binds `ChatViewModel` + `ThemeModeViewModel`; androidModule binds `ChatLogger`/`AndroidDictation`/`SystemMemoryStatusProvider` and drops the moved VMs + the explicit `ChatViewModel` binding; desktopModule binds `ChatLogger`/`DesktopSystemMemoryStatusProvider`/`DesktopThemePreferences`. NEW `:desktopApp` `desktopAppModule` binds `WarmModel` (singleton, shared with the tray runtime) + `DesktopChatSessionController`; `Main.kt` loads it and renders `AppNavHost` in the window (replacing `QueueStatusScreen`). Chat VM/helper tests repackaged to follow their subjects + gained the `logger` ctor arg. All 7 gates green.

**Checkpointed after 8d** at a clean green state. Only inc 9 (shell shrink + version bump) remains.

**Increment 9 (`4e4f2f9`) — shell shrink + release prep.** The screen-by-screen cutover already shrank `:androidApp` to the thin shell — `MainActivity`, `MobileAgentApplication`, `AndroidKoinModule`, the thin `MainScreen` wrapper + `MainViewModel`, the Android-only WorkManager `DownloadScreen`/`DownloadViewModel`, `Theme` + `SharedPreferencesThemePreferences`, `SpikeActivity`/`SpikeViewModel`, and the Android-only services + actuals. No dead `app.ui.*` `.kt` files remained (the moves removed them; empty package dirs are untracked). `:desktopApp` hosts the real shared `AppNavHost` in its window (inc 8d) with the tray + warm-model runtime intact. Bumped `appVersionName` `0.0.3-beta` → `0.1.0` (`androidApp/build.gradle.kts`) for the `feature/desktop-cmp` → `main` merge. `:androidApp:assembleDebug` green (prints `versionName : 0.1.0`).

**PHASE 9 COMPLETE.** UI cutover done — all screens (incl. Chat) live in shared `:ui`; both shells render the same `AppNavHost`.

---

## MERGED to `main` — v0.1.0 (PR #53, 2026-06-02)

`feature/desktop-cmp` → `main` merged via **PR #53** (merge commit `8963a06`, 2026-06-02). `main` is now the CMP + Koin desktop-port reality. **The `v0.1.0` git tag is still pending** (release-tag the merge commit when ready; the installer `packageVersion` stays `1.0.0` per the Phase-8 note).

**On-device validation done (Linux desktop + Pixel 7) — post-merge / late-branch fixes:**
- **Desktop aux engines never warmed** (`139120f`): classifier/embedder are inert until `warmUp()`; Android drives it from the Chat RESUME hook (#22) but desktop had no call site → eager warm on the app scope at startup.
- **Android `libLiteRt.so` native collision** (`329f37b`, invariant #40): moving the litert deps into `:shared` flipped AGP's non-deterministic `pickFirst` to litertlm's symbol-less copy → `UnsatisfiedLinkError: Environment.nativeCreate`. Fixed by extracting litert's natives into project-local jniLibs (which always win `pickFirst`).
- **`MemoryViewModel` DI crash** (`e8e16f9`): `viewModelOf` doesn't honour ctor defaults, so its `CoroutineDispatcher = Dispatchers.IO` param (bound nowhere) threw at first render → bound with an explicit `viewModel { }` lambda.
- **Desktop chat accelerator banner** (`d0a0800`): `DesktopChatSessionController` hardcoded `Loaded(CPU)` → now reports the resident handle's real accelerator (GPU on a GPU-capable native).
- **Desktop eager GGUF-to-GPU load at startup** (`75c6190`): `Main` warms the LLM on the app scope (gated on model presence) so the first turn doesn't cold-load and the banner shows the accelerator up front; Android stays lazy (#22).
- **CI:** the classifier `regression-gate` workflow is now `workflow_dispatch`-only (`a88590e`) — its frozen splits + checkpoint are gitignored, so it never ran on a stock PR checkout.

**Still operator-verify going forward:** real llama.cpp GPU offload throughput; installer packaging on macOS/Windows runners; voice/vision round-trips.
