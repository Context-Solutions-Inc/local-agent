# Context for Claude

This file is auto-loaded into every Claude Code session. Goal: don't make a
fresh-context Claude rediscover what M0 already cost time to learn.

## Always read first

Before suggesting architecture, scope, or code structure, read:

1. `PRD.md` — product spec (locked, treat as ground truth)
2. `PHASE1_PLAN.md` — milestone plan (status updated as we go)
3. `docs/M0_DECISION_MEMO.md` — ratified Phase 1 hardware/runtime decisions
4. `SYSTEM_PROMPT.md` — system prompt construction spec
5. `CLASSIFIER_DATASETS.md` — pre-flight + memory extractor dataset spec
6. `docs/M3_PLAN.md` — M3 phase-by-phase plan + ratified decisions
7. `docs/M3_M4_HANDOFF.md` — operational handoff for the v1.0 classifier (M4 starting point)
8. `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` — classifier spec, eval metrics, known weaknesses
9. `docs/M4_PLAN.md` — M4 phase log + decisions (pre-flight integration)
10. `docs/M4_M5_HANDOFF.md` — operational handoff for memory subsystem starting point
11. `docs/M5_PLAN.md` — M5 phase log + decisions (memory subsystem)
12. `docs/M5_M6_HANDOFF.md` — operational handoff into M6 (telemetry, schema migration must-do, deferred items)
13. `docs/M6_KICKOFF.md` — kickoff prompt for M6 (read only when starting M6)

## Project at a glance

On-device AI assistant for Android. **Pixel 7 + Android 16 only** for Phase 1.

- **Inference runtime:** LiteRT-LM 0.10.2 (`com.google.ai.edge.litertlm:litertlm-android`)
- **Model:** Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, 2.58 GB)
  — E4B was tried and ruled out (LMKD-induced thrash on 8 GB Pixel 7)
- **Accelerator:** GPU (Mali-G710 via Play Services TFLite OpenCL delegate).
  NPU not exposed by Tensor G2; CPU is the runtime fallback when GPU init throws
  (M1 WS-1 Phase A — `LiteRtInferenceEngine.tryInitialize`).
- **Architecture:** KMP shared module (`:shared`) + Android Compose shell (`:androidApp`).
  iOS targets stubbed for Phase 2.
- **Toolchain:** JDK 17, Gradle 9.3.1, AGP 9.1.1, Kotlin 2.3.21, KSP 2.3.7, Hilt 2.59.2
- **Pre-flight + memory classifier:** single shared DistilBERT-base encoder + 3 task heads,
  exported to `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB INT8). Three
  outputs in one forward pass. M4 / WS-8 wires this via Play Services LiteRT.
- **Classifier training pipeline:** `classifier-training/` Python package (own venv,
  uses host RTX 5090 + CUDA). Generation defaults to local Ollama (`qwen3.5:9b`),
  not Claude. CLIs: `ct-validate`, `ct-stats`, `ct-dedup`, `ct-review`, `ct-fill`,
  `ct-expand-pairs`, `ct-generate-{preflight,memory}`, `ct-train-classifier`,
  `ct-eval-classifier`, `ct-quantize`, `ct-export-litert`, `ct-bench-pixel7`.

## Hard invariants

Things M0 surfaced the hard way — don't rediscover:

1. **Every LiteRT-LM call must run off the main thread.** `Engine.initialize()`
   blocks 4-8 s on Pixel 7 and will ANR. `loadModel`/`generate` in
   `LiteRtInferenceEngine` already wrap in `withContext(Dispatchers.IO)` /
   `flowOn(Dispatchers.IO)` — keep it that way for any new caller.
2. **`GenerationRequest.maxTokens` is a no-op in LiteRT-LM 0.10.2.** Public API
   doesn't surface a per-call cap; the model generates until end-of-turn. To
   enforce stop conditions, cancel the Flow at the parser layer (M3 work).
3. **`Backend.GPU()` requires Play Services TFLite at runtime.** Without
   `play-services-tflite-gpu:16.4.0` on the classpath, GPU init throws
   `Cannot find OpenCL library on this device`. Keep these deps in `:shared`'s
   `androidMain`. Devices without recent Play Services need a CPU fallback
   (M1 work, see M0 memo §5 Risk 1).
4. **Pixel CDEV throttling can drop GPU clocks before `PowerManager.currentThermalStatus`
   reflects it.** If we need accurate throttling signal, infer from measured
   tok/s drift; the high-level thermal API undercounts.
5. **`:shared` uses `com.android.kotlin.multiplatform.library`** (not the old
   `com.android.library` + `kotlin.multiplatform` combo, which AGP 9 forbids).
   Android config goes inside `kotlin { android { } }`.
6. **WorkManager's `SystemForegroundService` needs `foregroundServiceType`
   merged in our manifest.** Any worker that calls `setForeground(...)` will
   crash the worker process with `IllegalArgumentException: foregroundServiceType
   0x1 is not a subset of 0x0` unless we declare the type via `tools:node="merge"`
   in `AndroidManifest.xml`. Currently `dataSync` for the model download; add
   more bits (e.g. `|specialUse`) if a future worker needs them.
7. **`POST_NOTIFICATIONS` must be requested at runtime on Android 13+.** The
   manifest permission alone is not enough — the OS silently suppresses every
   notification (including FGS-required ones) until the user grants it.
   `MainActivity.ensureNotificationPermission` does this on first launch.
8. **Tool calling MUST go through LiteRT-LM's structured channel.** Putting a
   JSON tool schema in the system prompt text is not enough — Gemma 4
   LiteRT-LM 0.10.2 only treats a tool as callable when it's registered via
   `ConversationConfig.tools` (using `tool(OpenApiTool)` to wrap a description
   JSON). With text-only schemas the model defaults to "I don't have real-time
   data" refusals. See `LiteRtInferenceEngine.toLiteRtToolProvider`.
9. **Reuse the same `Conversation` across the multi-step tool-call cycle.**
   Per https://ai.google.dev/edge/litert-lm/android, a turn that involves a
   tool call is: `sendMessageAsync(userText)` → collect → if `Message.toolCalls`
   populated, execute tool → `sendMessageAsync(Message.tool(...))` on the
   **same conversation** → collect again. Re-creating the conversation per
   step and replaying via `initialMessages` does NOT preserve Gemma's
   call↔response correlation; the model just re-emits the call until the
   per-turn cap fires. `LiteRtInferenceEngine.generate` drives this loop
   internally and exposes a `ToolDispatcher` callback to the agent layer.
10. **`Content.ToolResponse(name, response)` needs a structured payload.**
    `JsonConvertersKt.toJsonElement` (Gson-based) converts Maps/Lists/
    primitives into structured JSON but treats a `String` as a JSON-quoted
    string — so passing the raw payload `"[{\"title\":...}]"` makes Gemma
    see `"response": "[{\"title\":...}]"` (a quoted blob) instead of an
    array. The engine parses the agent's payload with kotlinx.serialization
    and converts to `List<Map<String, Any?>>` before handing it to
    `Content.ToolResponse`. See `LiteRtInferenceEngine.parseAsStructured`.
11. **Don't pre-template `<start_of_turn>...<end_of_turn>` markers in the
    prompt.** LiteRT-LM applies Gemma's chat template internally when you
    use the structured `ConversationConfig` (`systemInstruction`,
    `initialMessages`) + `sendMessageAsync`. Hand-rolling the markers and
    sending the whole thing as one big user message gets the prompt
    double-wrapped. `PromptAssembler.assembleStructured` returns a
    `StructuredPrompt(systemInstruction, history, tools)` — no markers.

`MarkerFunctionCallParser` is kept in the codebase but is **legacy**: it was
the original text-marker workaround. With invariants 8–11 in place,
LiteRT-LM surfaces tool calls structurally via `Message.toolCalls` and the
parser is unused in the production path (still has tests).

12. **The classifier .tflite has 2 inputs and 3 outputs identified by `tensor.name()`,
    NOT interpreter index.** Play Services LiteRT 16.4.0 on Pixel 7 permutes BOTH input
    and output indices away from name order, and the failure mode is silent: garbage
    embeddings or swapped heads with no exception, just nonsense logits.
    - **Inputs** (`int64`, shape `[1,128]`): `serving_default_args_0:0` → input_ids,
      `serving_default_args_1:0` → attention_mask. Dispatch by parsing the `_N:0`
      suffix from `interpreter.getInputTensor(i).name()`. (M4 Phase B caught this
      empirically — feeding `[ids, mask]` to `runForMultipleInputsOutputs` in argument
      order produced raw logits in the thousands; reordering by name yielded calibrated
      logits in the [-5, +5] range matching the Python `ai-edge-litert` reference.)
    - **Outputs** (`float32`): `StatefulPartitionedCall:0` → **preflight_logits** `[1,3]`
      ordered `[search_required, search_not_required, ambiguous]`, `:1` →
      **presence_logits** `[1,2]` ordered `[no_extraction, has_extraction]`, `:2` →
      **category_logits** `[1,6]` (multi-label sigmoid) ordered `[personal_identity,
      preference, professional, interest, relationship, temporary_context]`. Verified
      by the M4 Phase A spike: runtime returned `[idx 0]=name :1`, `[idx 1]=name :0`,
      `[idx 2]=name :2`. Dispatch by parsing the `:N` suffix from
      `interpreter.getOutputTensor(i).name()`, with shape as a sanity check
      (`[1,3]`/`[1,2]`/`[1,6]` are all unique on this graph). Hardcoding either
      interpreter index ships a silent swap.

13. **Classifier tokenizer must match training-time `distilbert-base-uncased` exactly.**
    Different vocab (cased vs uncased), different sub-word splits, or a stale `vocab.txt`
    silently degrade the classifier with no error message. The Android side bundles the
    same 30,522-entry WordPiece vocab and lower-cases input. A fixture test in `:androidApp`
    must assert input_ids match between the Python tokenizer and the Kotlin runtime on a
    known string — without it, drift is silent.

14. **Pre-flight thresholds (0.85 high / 0.15 low) are CONFIGURABLE per PRD §3.2.1, not
    constants.** Surface them via the shipped JSON config the agent reads at startup.
    Hard-coding blocks post-launch telemetry-driven tuning, which is the documented path
    to closing the v1.0 §7 precision gap (model card weakness #1, #2).

15. **Classifier .tflite sequence length is statically baked at export (currently 128).**
    Different lengths require re-export via `ct-export-litert --max-length N`. Long queries
    are truncated; if telemetry shows real queries averaging >50 sub-word tokens, re-export
    at 256.

16. **`ai-edge-torch` was renamed to `litert-torch` in 2025.** Both are installed in the
    classifier-training venv but only `litert_torch` is actively maintained. Use
    `import litert_torch` for new code; the export driver falls back to `ai_edge_torch` only
    for older saved sessions. Quantization recipes in `litert_torch.generative.quantize` are
    LLM-specific (not for encoder classifiers); use `ai_edge_quantizer.Quantizer` with
    `MIN_MAX_UNIFORM_QUANT` for encoder INT8 weight-only quant.

17. **`models/` is gitignored — model cards live in `docs/`.** Binary artifacts (`.tflite`,
    `.pt`) are excluded by `.gitignore`'s `models/` rule. Keep the model card at
    `docs/preflight_memory_shared_vX.Y.Z_MODEL_CARD.md` so it tracks in git alongside the
    handoff note.

18. **The pre-flight classifier MUST run on `com.google.ai.edge.litert:litert:2.x`
    — NOT `org.tensorflow:tensorflow-lite` and NOT Play Services TFLite.** Both
    classic TFLite runtimes produce numerically broken outputs for our
    `ai-edge-quantizer` weight-only INT8 model (logits ~1500x larger magnitude
    than the Python reference, every query collapsing to one dominant class).
    Verified by the M4 Phase B diagnostic: same model, same tokens, same int64
    inputs — Python `[1.902, -1.22, -0.672]`, both Android runtimes
    `[1234, -2365, 3058]`. No exception, just silently wrong arithmetic.
    `com.google.ai.edge.litert:litert:2.1.4` is the Android port of Python's
    `ai-edge-litert` runtime — the one ai-edge-quantizer's export tooling
    actually targets. Different package (`com.google.ai.edge.litert.CompiledModel`,
    not `org.tensorflow.lite.InterpreterApi`), different native libs
    (`libLiteRt.so` vs `libtensorflowlite_jni.so`). LiteRT-LM (Gemma 4) keeps
    using its own runtime via `play-services-tflite-*` — both coexist on the
    classpath because each is opted-into explicitly. **Class-collision
    workaround:** litert-2.1.4.aar bundles its own copy of `org.tensorflow.lite.*`
    classes; play-services-tflite-java pulls `tensorflow-lite-api` transitively
    with the same class names. AGP rejects duplicates. Resolved via
    `configurations.matching { … }.configureEach { exclude(group =
    "org.tensorflow", module = "tensorflow-lite-api") }` in
    `:shared/build.gradle.kts` and `:androidApp/build.gradle.kts`. Both ABI-
    compatible — picking either copy works.

19. **AGP 9 KMP source-set DSL doesn't accept the closure form for catalog
    `Provider` deps.** In `kotlin { sourceSets { androidMain.dependencies { … } } }`,
    `implementation(libs.foo) { exclude(...) }` fails to compile with
    "Argument type mismatch: actual type is 'Provider<…>', but 'String' was
    expected." `.get()` resolves the Provider to a
    `MinimalExternalModuleDependency`, but that type is immutable — `exclude {}`
    on it throws "Minimal dependencies are immutable" at config time. The
    workaround is configuration-level `configurations.matching { it.name.startsWith
    ("androidMain") || ... }.configureEach { exclude(...) }` outside the
    `kotlin {}` block, OR plain `dependencies { add("androidMainImplementation", …)
    { exclude(…) } }`. Hit during M4 when wiring the litert dep alongside
    play-services-tflite (inv. #18); same pattern will recur if M5's embedder
    has transitive collisions.

## Build & run

```bash
# Build
cd android-app && ./gradlew :androidApp:assembleDebug

# Install + launch on connected Pixel 7
./gradlew :androidApp:installDebug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity

# Dev iteration without waiting on real model load (uses StubInferenceEngine)
./gradlew :androidApp:installDebug -PuseStubEngine=true

# M0 spike harness (Activity entry point, stays in the build)
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity

# Pull a spike result JSON
adb shell run-as com.contextsolutions.mobileagent.debug ls files/spike-results/
adb shell run-as com.contextsolutions.mobileagent.debug cat files/spike-results/spike-<runId>.json > ~/spike-results/<file>.json
```

### Wireless adb (Pixel 7's USB connection has been unstable)

Pair once, then re-`connect` after each phone reboot (the connection port changes;
the pairing persists). Phone: Settings → System → Developer options → Wireless
debugging → enable, then "Pair device with pairing code".

```bash
adb pair <phone-ip>:<pairing-port>     # one-time, takes the 6-digit code
adb connect <phone-ip>:<connect-port>  # after reboot or WiFi drop
```

### Skip the download during dev iteration

Sideload the model so a fresh `installDebug` doesn't burn 5–10 minutes
re-downloading. The production read path is `filesDir/models/...`:

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as com.contextsolutions.mobileagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'
```

### Model file locations

- **Production (M1+):** `filesDir/models/gemma-4-E2B-it.litertlm` — internal
  storage, written by `ModelDownloadWorker`, read by `ModelInventory.localFile`.
- **M0 spike:** `/sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/gemma-4-E2B-it.litertlm`
  — external app dir for `adb push` convenience. The two paths coexist; production
  doesn't read the spike one and vice versa.
- **Classifier (M3 ship):** `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB)
  — gitignored. M4 bundles into `:androidApp/src/main/assets/` (or downloads alongside
  Gemma via WorkManager). FP32 reference at `models/preflight_memory_shared_v1.0.0.tflite`
  (264.6 MB) is for debugging only — never ship.

### Classifier build & run (Phase 1 M3 / M4 / WS-14)

```bash
# One-time setup (separate venv from android-app)
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"           # core (gen, review, dedup, stats)
pip install -e ".[training]"      # adds torch/transformers/litert-torch (requires CUDA)

# Generation (Ollama-backed, $0 marginal cost)
ollama pull qwen3.5:9b            # or set OLLAMA_MODEL to a different tag
ct-fill preflight --out ../datasets/preflight/preflight_v0.1.0.jsonl --multiplier 1.0
ct-fill memory    --out ../datasets/memory/memory_v0.1.0.jsonl    --multiplier 1.0
ct-expand-pairs preflight --variants-per-side 8 --out ../datasets/preflight/preflight_v0.1.0.jsonl
ct-expand-pairs memory    --variants-per-side 5 --out ../datasets/memory/memory_v0.1.0.jsonl
ct-dedup ../datasets/preflight/preflight_v0.1.0.jsonl --apply
ct-stats ../datasets/preflight/preflight_v0.1.0.jsonl

# Training + eval + export (full v1.0 reproduction takes ~10 min on RTX 5090)
ct-train-classifier --preflight-jsonl ../datasets/preflight/preflight_v1.0.0.jsonl \
    --memory-jsonl ../datasets/memory/memory_v1.0.0.jsonl \
    --output-dir ../eval/runs/$(date +%Y%m%d_%H%M%S) \
    --epochs 5 --batch-size 32 --lr 2e-5 --seed 42
ct-eval-classifier --ckpt ../eval/runs/<ts>/best.pt \
    --preflight-jsonl ../datasets/preflight/preflight_v1.0.0.jsonl \
    --memory-jsonl    ../datasets/memory/memory_v1.0.0.jsonl \
    --output-dir      ../eval/runs/<ts>
ct-export-litert --ckpt ../eval/runs/<ts>/best.pt \
    --output ../models/preflight_memory_shared_v1.0.0_int8.tflite \
    --max-length 128 --int8

# Pixel 7 latency benchmark (host-CPU proxy; real Pixel 7 numbers come from
# the :androidApp instrumentation test ClassifierLatencyBenchmark — see below)
ct-bench-pixel7 --tflite ../models/preflight_memory_shared_v1.0.0_int8.tflite \
    --output ../eval/runs/<ts>/pixel7_latency.json --host-proxy

# WS-14 regression gate (M4 Phase E) — verifies SHA-256s, runs eval on
# regression split, diffs against v1.0 baseline, exits 0/1/2/3
# (PASS / SHA-mismatch / regression / infrastructure error). Run before
# any new .tflite lands in models/.
ct-regression-check --ckpt ../eval/runs/<ts>/best.pt
ct-regression-check --skip-eval --ckpt path/to/metrics.json   # for hosted CI flows
```

### M4 classifier instrumentation tests (Pixel 7)

```bash
# End-to-end: real assets + WordPieceTokenizer + LiteRtClassifierEngine
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.classifier.ClassifierEndToEndTest

# Latency: 200 warmup + 1000 measured forward passes (M4 gate p95 < 150 ms;
# PRD §2.3 80 ms aspiration deferred to v1.x int32 re-export)
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.classifier.ClassifierLatencyBenchmark

# Diagnostic kept for M4 Phase B reference (compares FROM_SYSTEM_ONLY vs
# FROM_APPLICATION_ONLY runtimes — both broken with classic TFLite, neither
# matters now that we use ai-edge-litert per inv. #18)
./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.classifier.ClassifierDiagnosticTest
```

### Secrets

`secrets.properties` lives at `android-app/secrets.properties` (next to
`settings.gradle.kts`), NOT at the repo root. Gradle's `rootProject` is
`android-app/` because that's where `settings.gradle.kts` is, and `rootProject.file(...)`
resolves accordingly. `mobile-agent/secrets.properties` is silently ignored — easy
mistake to make. See `android-app/secrets.properties.example` for the fields.

## Working norms

- **Don't commit unless explicitly asked.** The user reviews diffs and decides.
- **Read the official integration guide BEFORE introspecting the JAR.**
  `https://ai.google.dev/edge/litert-lm/android` documents the intended usage
  pattern (tool registration, multi-turn conversations, etc.) which can't be
  reverse-engineered from API signatures alone. In M2 we burned several
  iterations replaying conversation history through `initialMessages` because
  we hadn't read the "reuse the same Conversation" guidance. Read the docs
  page first, then use `javap -cp <jar> -p com.google.ai.edge.litertlm.X` to
  pin down exact signatures (the docs are partial; the JAR is authoritative
  on types).
- **Keep all LiteRT-LM types behind `LiteRtInferenceEngine`.** The
  `InferenceEngine` interface in `commonMain` is the seam; don't import
  `com.google.ai.edge.litertlm.*` anywhere else.
- **Documentation hygiene:** when a Phase 1 decision changes (e.g., model
  swap, KV cache size), update both `PHASE1_PLAN.md` and
  `M0_DECISION_MEMO.md`. Don't rely on commit messages alone.
- **Brief responses.** The user reads diffs and code; don't recap them in prose.

## Status

| Milestone | Status |
|---|---|
| M0 Foundation & spike | ✅ Complete 2026-05-05 |
| M1 Chat MVP — WS-1 | ✅ Complete 2026-05-05. All 12 exit-gate drills passed on Pixel 7. |
| M2 Web search & agent loop (incl. M1 WS-2/3/11) | ✅ Complete 2026-05-07. End-to-end chat + tool-calling + UI. Single LiteRT-LM `Conversation` per user turn, structured tool registration, `ToolDispatcher` callback. 107 unit tests. Known limitation: Brave snippets are page descriptions; works well for sports/stocks/news but doesn't yield raw weather numbers without page fetching. |
| M3 Datasets & classifier training | ✅ Complete 2026-05-09. Datasets `preflight_v1.0.0` (11,670) + `memory_v1.0.0` (7,707) frozen with regression-set SHA-256s in manifests. Single shared DistilBERT-base + 3 task heads exported to `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB INT8). §7 GATE: FAIL by 4-7pp on two pre-flight metrics — defensible v1 with documented v1.x improvement path (precision ceiling is dataset-level boundary noise, not tunable). Memory presence passes (92.2% test / 96.2% regression). 26 unit tests in `classifier-training/`. Detailed phase log in `docs/M3_PLAN.md`; M4 handoff at `docs/M3_M4_HANDOFF.md`. |
| M4 Pre-flight integration | ✅ Complete 2026-05-10. Classifier wired into `AgentLoop` via `:shared/commonMain/classifier/PreflightRouter` (engine in `:shared/androidMain` on `com.google.ai.edge.litert:litert:2.1.4`). Three-band routing per PRD §3.2.1; deterministic-only rewriter (memory-context queries abort to FallThrough until M5). Pixel 7 CPU latency p95=113 ms (M4 gate <150 ms ✓; PRD §2.3 80 ms aspiration tracked as model card v1.x #5 — int32 input re-export). End-to-end on real Pixel 7: pre-flight fires for time-sensitive queries with rewritten search queries; M2 path unchanged for middle/low band. WS-14 `ct-regression-check` CLI shipped — verifies SHA-256s, runs `ct-eval-classifier --split regression`, gates on >2pp regression across 19 metrics. 142 Kotlin tests + 40 Python tests. Detailed phase log in `docs/M4_PLAN.md`; M5 handoff at `docs/M4_M5_HANDOFF.md`. |
| M5 Memory subsystem | ✅ Complete 2026-05-10. all-MiniLM-L6-v2 INT8 embedder (23.5 MB) on `com.google.ai.edge.litert:litert:2.1.4`; SQLite memory store (BLOB embeddings, brute-force cosine, eviction cascade); retrieval injected before pre-flight + `[MEMORY CONTEXT BLOCK]` (SYSTEM_PROMPT §5); possessive substitution in `QueryRewriter` ("did my team win" → "did philadelphia eagles win"); post-turn extraction with explicit remember/forget commands (broadened regex covers possessives + determiners + interrogatives); MemoryScreen + ConversationMemoryListScreen + chat-bar badge; storage hardening audit (no memory text in any log path). Pixel 7 end-to-end retrieval p95 = 72 ms (under PRD §3.2.4's 100 ms). 265 Kotlin unit tests (+123 over M4). Detailed phase log in `docs/M5_PLAN.md`; M6 handoff at `docs/M5_M6_HANDOFF.md`. |
| M6 Polish, eval, telemetry | Not started |
| M7 Closed beta → Play Store | Not started |

## M2 architecture cheat sheet (for follow-ups)

- **Agent layer (`:shared/commonMain/agent/`)** — `AgentLoop` runs a single
  user turn: assembles `StructuredPrompt`, calls `session.generate` once with
  a `ToolDispatcher` closure that owns `SearchService` calls, citation
  accumulation, and the per-turn cap. No per-step generation loop.
- **Engine (`:shared/androidMain/inference/LiteRtInferenceEngine.kt`)** —
  drives the multi-step tool cycle on a single `Conversation`:
  `sendMessageAsync(userText)` → if `Message.toolCalls` populated, dispatch →
  `sendMessageAsync(Message.tool(Contents.of(toolResponses)))` → loop until
  no calls. `automaticToolCalling = false` so we (the agent) execute, not
  the runtime.
- **Search (`:shared/commonMain/search/`)** — `BraveSearchClient` over Ktor;
  `SearchCacheDao` (TTL-by-category, LRU at 500); `SearchService` composes
  key + cache + client and gates on `isEnabled() && hasKey()`. Errors are
  returned as `SearchOutcome.Error` (never thrown) so the model can adapt.
- **UI (`:androidApp/.../ui/`)** — `ChatScreen` projects `AgentEvent` into
  bubbles + streaming partial + search-status chip + citation chips.
  `SettingsScreen` for BYOK key / search toggle / cache clear.
- **Tests** — Unit tests live in `:androidApp/src/test/` (the AGP 9 KMP
  library plugin doesn't wire host tests for `:shared` out of the box; the
  JDBC SQLite driver is a `testImplementation` dep there).

## M3 architecture cheat sheet (for M4 / M5 follow-ups)

- **Classifier package (`classifier-training/`)** — Python project with its own
  `.venv`. Generation defaults to local Ollama (`qwen3.5:9b`); set
  `CT_GEN_BACKEND=claude` + `ANTHROPIC_API_KEY` and `pip install -e '.[claude]'`
  for the optional Claude path. Schemas validated via Pydantic; every JSONL
  row passes through schema validation before training/eval consumes it.
- **Stratified generation driver (`ct-fill`)** — picks the most-underweight
  cell per batch, rotates target_confidence to hit §2.2's 70/25/5, uses
  in-batch dedup against the canonical text set so duplicates don't count
  toward cell targets. Per-batch dup rate dropped from 41% (pre-fix) to 4-5%
  with the forbidden-exemplar list in the prompts.
- **Adversarial pair authoring (`ct-expand-pairs`)** — 80 hand-authored
  preflight prototypes (5 §2.4 pair types × 16 each) and 48 memory hard-case
  prototypes (implicit_vs_explicit, temporary_vs_stable, sensitive ×16). The
  driver expands each prototype side via Ollama into 5-8 rephrasings sharing
  a `pair_id`, forces one variant per side into the regression split.
- **Eval harness (`ct-eval-classifier`)** — emits a Markdown + JSON report
  with the single-line `M3 GATE: PASS/FAIL` at top, three-band routing
  simulation (>0.85 / 0.15-0.85 / <0.15), threshold sweep across [0.50, 0.95]
  with auto-detected ship_threshold, adversarial-pair accuracy, per-class
  P/R/F1, confusion matrix, host-CPU latency proxy. Exit code reflects gate
  state for CI use (M4 / WS-14).
- **Phase F finding** — multi-task fine-tuning beats two-separate-classifiers
  on this dataset (preflight-only iter 2 was *worse* by 2-3pp). The shared
  encoder is the right architecture; the v1.0 precision ceiling is dataset-level
  boundary noise between search_required and ambiguous, not solvable by
  hyperparameter tuning. Improvement path is telemetry-driven dataset
  expansion in v1.x.
- **Phase G finding** — `ai-edge-quantizer` weight-only INT8 channel-wise
  produces a usable 67.7 MB .tflite from the FP32 PyTorch checkpoint. The
  `litert-torch` (formerly `ai-edge-torch`) generative quant recipes are
  LLM-specific and don't reduce encoder size — use `ai_edge_quantizer.Quantizer`
  with `MIN_MAX_UNIFORM_QUANT` instead. PyTorch dynamic INT8 (`torch.ao.quantization.quantize_dynamic`)
  exists in `ct-quantize` for CPU-only debugging but is NOT the .tflite
  ship path.

## M4 architecture cheat sheet (for M5 / M6 follow-ups)

- **Classifier package (`:shared/commonMain/classifier/`)** —
  `ClassifierEngine` interface, `ClassifierOutput` (3 logit arrays from
  one forward pass), `WordPieceTokenizer` (byte-exact against HF
  `distilbert-base-uncased` over 22 fixtures), `Vocab`, `PreflightConfig`
  (kotlinx-serializable JSON), `PreflightRouter`, `QueryRewriter`,
  `internal/Softmax.kt` (numerically-stable softmax/sigmoid handling huge
  INT8 logit magnitudes).
- **Engine (`:shared/androidMain/classifier/LiteRtClassifierEngine.kt`)**
  — backed by `com.google.ai.edge.litert:litert:2.1.4` (Android port of
  Python's ai-edge-litert; matches the export tooling). NOT
  `org.tensorflow:tensorflow-lite` (broken outputs on this graph) and NOT
  Play Services TFLite (also broken; see CLAUDE.md inv. #18). Output
  index dispatch by parsing the `:N` suffix of `tensor.name()` (the
  runtime permutes interpreter index away from name order — inv. #12).
  GPU delegate refuses to compile this graph (`BROADCAST_TO`,
  `EMBEDDING_LOOKUP`, `CAST INT64→FLOAT32` not supported); CPU XNNPACK
  is the only path. p95=113 ms on Pixel 7 — accepted vs PRD §2.3 80 ms
  aspiration; v1.x int32 re-export should close the gap.
- **Lazy load on chat-screen entry** — `ChatViewModel.init` kicks off
  `engine.warmUp()` on `Dispatchers.IO`; load latency (~200-500 ms on
  Pixel 7) hides behind user typing time. Once loaded, stays resident
  for the app lifetime. Failure to load returns null from `warmUp()`
  (no throw); router emits `FallThrough(ClassifierUnavailable)`. This
  is a deliberate deviation from PRD §4.2's "loaded at app start"
  phrasing — keeps app cold start clean and skips the load entirely
  when the user only opens settings; trade-off is a one-time first-chat
  warm-up cost. Resolved in `docs/M4_PLAN.md` §7.
- **Router (`PreflightRouter.route(query)`)** — three-band per PRD
  §3.2.1: `>0.85` → run rewriter → FireSearch (or FallThrough on rewriter
  abort), `<0.15` → SkipSearch (web_search tool stays registered), middle
  → FallThrough(MiddleBand). `searchAvailable` short-circuits to
  `SearchDisabled` before classify to save inference cycles. Logs every
  decision at INFO via injected `(String) -> Unit` callback.
- **Rewriter (`QueryRewriter`)** — deterministic-only. Date/time
  substitution from `TimeContext` (today, yesterday, last night, last
  week, last month, last year + variants). Memory-reference detection
  (`my X`, `where I live`, `i live in`) returns null → router emits
  `FallThrough(RewriterAbort)`. No Gemma fallback (defeats round-trip
  saving). M5 promotes "my team"-style queries to FireSearch via memory
  retrieval substitution.
- **AgentLoop integration (`:shared/commonMain/agent/AgentLoop.kt`)** —
  `route()` runs before prompt assembly. On `FireSearch`: emit
  `SearchStarted(rewrittenQuery)`, run `searchService.search()`, append a
  synthetic `Assistant(toolCall) + Tool(result)` pair to history (the
  Tool turn becomes the engine's "current message" via
  `sendMessageAsync(Message.tool(...))`), pass `preflightNotice = true`
  so the system prompt picks up `[PRE-FLIGHT NOTICE BLOCK]`
  (SYSTEM_PROMPT.md §6). Pre-flight tool calls do NOT count toward
  `maxToolCalls` (Gemma's budget remains 3).
- **Asset bundling** —
  `:androidApp/src/main/assets/preflight_memory_shared_v1.0.0_int8.tflite`
  (copied from `models/` at build time via a Gradle task that verifies
  SHA-256), `vocab.txt` (committed, 30,522 entries), `preflight_config.json`
  (committed, default thresholds 0.85 / 0.15, model_version pin).
- **Regression gate (`classifier-training/.../ci/regression_check.py`)**
  — `ct-regression-check --ckpt <new>.pt`. Verifies regression-JSONL
  SHA-256 against MANIFEST.md, runs `ct-eval-classifier --split regression`,
  diffs 19 gate metrics against
  `eval/runs/phaseF_full_20260509_162556/metrics.json`. Exits 0/1/2/3
  (PASS / SHA mismatch / regression / infrastructure error). Hosted-CI
  runner is M6.
- **Tests** — Kotlin: `WordPieceTokenizerFixtureTest`, `SoftmaxTest`,
  `QueryRewriterTest`, `PreflightRouterTest`, `AgentLoopPreflightTest`,
  `PlayServicesLiteRtSpikeTest` (instrumentation; legacy name, kept as
  documentation of Phase A finding), `ClassifierEndToEndTest`
  (instrumentation), `ClassifierLatencyBenchmark` (instrumentation).
  Python: `tests/test_regression_check.py` (14 unit tests for diff/SHA
  logic).

## M5 architecture cheat sheet (for M6 / v1.x follow-ups)

- **Memory package (`:shared/commonMain/memory/`)** —
  `Memory` data class (id, text, category, conversationId, createdAt,
  lastAccessed, accessCount, embedding[384], expiresAt), `MemoryCategory`
  enum mirroring schemas.py order, `EmbedderEngine` interface,
  `EmbedderOutput` (FloatArray[384]), `MemoryStore` interface,
  `SqlDelightMemoryStore` impl, `MemoryRetriever`, `MemoryExtractor`,
  `MemoryEvictor`, `RememberForgetDetector`, `TempContextDateParser`,
  `MemoryPreferences` interface; `internal/Cosine.kt`,
  `internal/EmbeddingBlob.kt` (LE Float32 ↔ ByteArray codec).
- **Embedder engine (`:shared/androidMain/memory/LiteRtEmbedderEngine.kt`)**
  — backed by `com.google.ai.edge.litert:litert:2.1.4` (same runtime as
  the M4 classifier; CLAUDE.md inv. #18). MiniLM exports with mean-pool +
  L2-norm baked into the graph, so the on-device output is a single
  normalised 384-dim vector — cosine reduces to dot product but the
  `cosine()` function still divides by norms for safety.
  - GPU rejected (`BROADCAST_TO`/`EMBEDDING_LOOKUP`/`CAST INT64→FLOAT32`
    unsupported, same as the classifier). CPU XNNPACK p95 = 40.68 ms.
  - One output tensor; no per-head dispatch. Verifies `[1, 384]` at
    warmUp.
- **Vocab dedup with classifier.** MiniLM uses bert-base-uncased
  WordPiece — byte-identical SHA-256 to the existing classifier
  `assets/vocab.txt`. Both engines reuse the single vocab + tokenizer
  singleton via Hilt. No `minilm_vocab.txt`.
- **Memory store (`SqlDelightMemoryStore`)** — brute-force cosine over
  all non-expired rows (1k cap → 31.87 ms p95 dominated by SQLite BLOB
  → ByteArray JNI copy, NOT the math). Top-K with atomic `last_accessed`
  + `access_count` bump in a transaction. Pre-loading embeddings to a
  resident `Map<String, FloatArray>` is the v1.x perf option if
  retrieval ever drifts above PRD §3.2.4's 100 ms budget; deferred
  because end-to-end p95 = 72 ms.
- **Schema (`Memories.sq`)** — added `access_count INTEGER NOT NULL
  DEFAULT 0` in M5. **No migration support is wired up**; existing dev
  installs need `pm clear com.contextsolutions.mobileagent.debug` (or
  uninstall) before Phase C+ M5 builds run. Documented inline in the
  .sq file. M6 should add `.sqm` migration files before any production
  rollout.
- **AgentLoop integration (`:shared/commonMain/agent/AgentLoop.kt`)** —
  retrieval runs **before** pre-flight (sequential, M5_PLAN §2). The
  retriever is nullable so M2/M4 callers compile unchanged. Returned
  memories flow into both `PreflightRouter.route(query, memories)` (so
  `QueryRewriter` can do possessive substitution) and
  `assembler.assembleStructured(memoryBlock = renderMemoryBlock(...))`
  (so the §5 block lands in the system prompt).
- **Possessive substitution (`QueryRewriter`)** — pattern table maps
  "my team" → PREFERENCE, "my company"/"where I work" → PROFESSIONAL,
  "where I live"/"my city" → PERSONAL_IDENTITY, "my partner|spouse|
  dog|cat|pet|kid" → RELATIONSHIP. Span heuristic finds the last
  copula/preposition (` is the `/` is `/` at `/` in `/` named `/etc.)
  and takes the tail (cap 5 tokens). Brittle by design — v1.x replaces
  with Gemma-generated canonical memory text per PRD §3.2.4 v1.x note.
- **Post-turn extraction (`MemoryExtractor`)** — wired via
  `ChatViewModel.runMemoryExtraction(event)` on `AgentEvent.Done`.
  Detector path (Remember/Forget) bypasses the classifier; classifier
  path runs `WordPieceTokenizer.encodePair(user, assistant)` →
  `engine.classify` → presence argMax → multi-label sigmoid > 0.5 →
  one memory per active category sharing the user-message embedding.
  Dedup via `findCosineMatch(>0.85)`. Forget uses the *retrieval*
  threshold (0.5), not the dedup threshold — fixed during on-device
  review. Eviction cascade runs once before any inserts. Every branch
  catches and logs; never throws.
- **Remember/forget regex coverage** — the connector alternation in
  `RememberForgetDetector` covers `that|this|me|i'?m|i\s+am|i|my|our|
  its|their|his|her|the|a|an|when|where|how|to|about` for both
  `REMEMBER_REGEX` and `FORGET_REGEX`. "Remember my dog's name is
  Evie" / "Forget my anniversary" / "Remember when we met" all match
  in v1; broadened during the on-device review.
- **`temporary_context` expiry (`TempContextDateParser`)** — parses ISO
  dates / "in N (days|weeks|months|years)" / "on (weekday)" / "today |
  tonight | tomorrow | next week | next month | next year". Returns
  null when nothing matches; extractor falls back to `now + 30d`.
- **MemoryPreferences toggle** — `SharedPreferencesMemoryPreferences`
  on Android (plain SharedPreferences, non-secret). Phase E
  `MemoryScreen` exposes the toggle. Each call to `extract()` re-reads
  the preference, so toggling takes effect on the next user turn.
- **UI (`:androidApp/.../app/ui/memory/`)** — `MemoryScreen` (grouped
  list + per-row delete + clear-all + creation toggle),
  `ConversationMemoryListScreen` (per-chat slice with category chip),
  `ConversationMemoryBadge` (chat top bar; hidden when count == 0),
  `MemoryViewModel` (single class drives both screens; refresh-on-entry
  via `LaunchedEffect(Unit)` pattern). `MainScreen` route added:
  Chat / Settings / MemoryManagement / ConversationMemory(id) sealed
  hierarchy with custom Saver. **In-place edit deferred to v1.x per
  Q4** — delete-and-re-state is the v1 workaround.
- **Edge-to-edge IME fix.** `enableEdgeToEdge()` in MainActivity stops
  `windowSoftInputMode=adjustResize` from auto-resizing the window —
  Compose has to consume the IME inset directly. Both `ChatScreen` and
  `SettingsScreen` content columns carry `Modifier.imePadding()` so
  the keyboard pushes the input up.
- **Storage hardening (WS-12)** — every `Log.*` / `logger` call in the
  memory pipeline emits counts, IDs, accelerator names, or `text.length`
  only — never raw memory or user text. Verified 2026-05-10 (M5_PLAN
  §6 / §7). Telemetry-exclusion comment markers in `Memories.sq` and
  `MemoryExtractor` so the M6 WS-13 telemetry builder cannot
  accidentally read memory content. Brave search payload only carries
  rewriter-substituted strings (memory-derived but not raw memory text)
  per PRD §4.4.
- **Tests** — Kotlin (host): `MemoryRetrieverTest`, `MemoryExtractorTest`,
  `MemoryEvictorTest`, `RememberForgetDetectorTest`,
  `TempContextDateParserTest`, `CosineTest`, `EmbeddingBlobTest`,
  `SqlDelightMemoryStoreTest`, `MinilmTokenizerFixtureTest`,
  `MemoryViewModelTest`, plus new cases in
  `QueryRewriterMemoryTest`, `AgentLoopMemoryTest`,
  `PromptAssemblerMemoryBlockTest`. Instrumentation:
  `EmbedderSpikeTest`, `EmbedderEndToEndTest`,
  `MemoryRetrievalLatencyBenchmark`. 265 unit + 4 instrumentation;
  every Phase D/E branch covered.
