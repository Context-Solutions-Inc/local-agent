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
| M3 Datasets & classifier training | Not started |
| M4 Pre-flight integration | Not started |
| M5 Memory subsystem | Not started |
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
