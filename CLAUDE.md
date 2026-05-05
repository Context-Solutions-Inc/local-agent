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
  NPU not exposed by Tensor G2; CPU is untested fallback.
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

The spike model lives at
`/sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/gemma-4-E2B-it.litertlm`
(per `docs/SPIKE_RUNBOOK.md` Stage 3).

## Working norms

- **Don't commit unless explicitly asked.** The user reviews diffs and decides.
- **When the published API docs are incomplete, introspect the JAR directly.**
  The LiteRT-LM HuggingFace model card and `ai.google.dev` docs were a partial
  subset of the actual API surface; `javap -cp <jar> -p com.google.ai.edge.litertlm.X`
  surfaced the real signatures during M0.
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
| M0 Foundation & spike | ✅ Complete 2026-05-05 (Decision 3 deferred to M1) |
| M1 Chat MVP | Not started |
| M2 Web search & agent loop | Not started |
| M3 Datasets & classifier training | Not started |
| M4 Pre-flight integration | Not started |
| M5 Memory subsystem | Not started |
| M6 Polish, eval, telemetry | Not started |
| M7 Closed beta → Play Store | Not started |
