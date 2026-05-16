# Mobile Agent — On-Device AI Assistant (Android)

Privacy-first on-device assistant running Gemma 4 E2B locally on Android, with Brave Search as the only outbound network dependency. See [PRD.md](PRD.md) for the full product spec and [PHASE1_PLAN.md](PHASE1_PLAN.md) for the implementation plan.

## Repository layout

```
.
├── PRD.md                          Product requirements
├── SYSTEM_PROMPT.md                System prompt construction spec
├── CLASSIFIER_DATASETS.md          Classifier dataset specifications
├── PHASE1_PLAN.md                  Phase 1 implementation plan
├── CLAUDE.md                       Context for Claude Code sessions (hard invariants)
├── agent_loop_state_diagram.svg    Agent loop reference diagram
│
├── android-app/                    KMP shared module + Android Compose shell
│   ├── shared/                     commonMain (agent loop, prompt, search, classifier,
│   │                                            memory, telemetry, storage, observability)
│   │                               androidMain (LiteRT-LM JNI, LiteRT classifier/embedder,
│   │                                            OkHttp engine, EncryptedSharedPrefs)
│   │                               iosMain (stubbed for Phase 2)
│   └── androidApp/                 Compose UI, Hilt DI, spike harness
│       └── src/main/assets/        Bundled .tflite + vocab + JSON config
│
├── classifier-training/            Python ML — dataset gen, fine-tuning, quantization,
│   └── src/classifier_training/    LiteRT export, regression-gate CLI
│
├── datasets/                       JSONL files (payloads gitignored, manifests committed)
│   ├── preflight/                  Pre-flight search classifier dataset (v1.0.0)
│   └── memory/                     Memory extraction classifier dataset (v1.0.0)
│
├── eval/                           Regression harnesses + canonical query suites
│   ├── canonical/                  15-query routing-layer eval (drives prompt-gate CI)
│   └── runs/                       Frozen training/eval artifacts
│
├── models/                         .tflite artifacts (gitignored; model cards in docs/)
│
├── .github/workflows/              Hosted CI — regression gate, prompt-eval gate
│
└── docs/                           Decision memo, phase plans (M3–M7), handoff notes,
                                    model card, privacy policy, Data Safety notes
```

## Targets

- **Devices:** Google Pixel 7 (Phase 1)
- **OS:** Android 16 (API 36)
- **Language stack:** Kotlin 2.3.21, Jetpack Compose, Kotlin Multiplatform (Android-only actuals in Phase 1)
- **Inference runtime:** LiteRT-LM 0.10.2 (Gemma) + `com.google.ai.edge.litert:litert:2.1.4` (classifier + embedder)
- **Models:**
  - Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, ~2.58 GB, downloaded on first run)
  - Pre-flight + memory classifier: shared DistilBERT-base encoder + 3 task heads (INT8, 67.7 MB, bundled)
  - Sentence embedder: all-MiniLM-L6-v2 (INT8, 23.5 MB, bundled)

## Status

**M0 through M6 complete.** Internal-quality build ready for closed beta on Pixel 7. End-to-end chat with web search, on-device pre-flight classifier, memory subsystem, opt-in telemetry, and first-run onboarding all run on device today.

| Milestone | State |
|---|---|
| M0 Foundation & spike | ✅ Complete 2026-05-05. Gemma 4 E2B on GPU validated on Pixel 7: first token p50 = 0.55 s, sustained 13.5 tok/s mean, peak PSS 3.52 GB. See `docs/M0_DECISION_MEMO.md`. |
| M1 WS-1 Inference foundation | ✅ Complete 2026-05-05. `LiteRtInferenceEngine` w/ CPU fallback + active-accelerator surfacing; `InferenceSessionManager` w/ 5-min idle unload + FGS lifecycle + `onTrimMemory` force-unload; resumable WorkManager-driven model download w/ SHA-256 verification; all 12 exit-gate drills passed. |
| M2 Web search & agent loop (incl. M1 WS-2/3/11) | ✅ Complete 2026-05-07. Single LiteRT-LM `Conversation` per turn with structured tool registration (`ConversationConfig.tools` + `Content.ToolResponse`); Brave Search client w/ category-TTL LRU cache (500); Compose chat UI with bubbles, streaming, citation chips, search-status chip; settings screen with BYOK key entry, search toggle, cache clear. |
| M3 Datasets & classifier training | ✅ Complete 2026-05-09. `preflight_v1.0.0` (11,670) + `memory_v1.0.0` (7,707) frozen with regression-set SHA-256s. Single shared DistilBERT-base + 3 task heads INT8 export at `models/preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB). PRD §7 GATE: defensible v1 with documented v1.x improvement path (precision ceiling is dataset-level boundary noise). See `docs/M3_PLAN.md` + `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md`. |
| M4 Pre-flight integration | ✅ Complete 2026-05-10. Classifier wired into `AgentLoop` via `PreflightRouter` (three-band routing per PRD §3.2.1); deterministic `QueryRewriter`; Pixel 7 CPU p95 = 113 ms; WS-14 `ct-regression-check` CLI ships. See `docs/M4_PLAN.md`. |
| M5 Memory subsystem | ✅ Complete 2026-05-10. all-MiniLM-L6-v2 INT8 embedder; SQLite memory store with brute-force cosine; retrieval injected before pre-flight + `[MEMORY CONTEXT BLOCK]`; possessive substitution in `QueryRewriter`; post-turn extraction with explicit Remember/Forget commands; `MemoryScreen` + per-conversation memory list + chat badge. End-to-end retrieval p95 = 72 ms (under PRD §3.2.4's 100 ms). See `docs/M5_PLAN.md`. |
| M6 Polish, eval, telemetry | ✅ Complete 2026-05-11. Schema v1→v2→v3 with `verifyMigrations` build gate; eager Gemma warm-up via `LifecycleResumeEffect` (first-token 1–3 s on cold-open-then-send); opt-in (default OFF) Firebase Analytics pipeline — 4 themed daily events, memory-exclusion canary test; Firebase Crashlytics behind `SafeCrashReporter` + `ContentRedactor`; 3-screen first-run onboarding; `ThermalBanner` at MODERATE/SEVERE + full block at CRITICAL; accessibility audit; 2 hosted-CI workflows (`regression-gate.yml`, `prompt-eval-gate.yml`). **318 unit tests.** See `docs/M6_PLAN.md` + `docs/M6_M7_HANDOFF.md`. |
| M7 Closed beta → public launch | Not started. See `docs/M7_PLAN.md`. |

Post-M6 increments tracked as M2.1–M2.7 in [PHASE1_PLAN.md](PHASE1_PLAN.md) §5: news.results enhancements, persisted multi-conversation history, on-device TODO list, proactive memory-pressure handling, icon-only header with system-RAM status indicator, and an explicit `remember`/`forget` short-circuit in the agent loop. **636 unit tests as of M2.7.**

## Building

### Prerequisites

- **JDK 17** (Temurin recommended). Set `JAVA_HOME` if not already on `PATH`.
- **Android SDK** with platform `android-36` (Android 16) and build-tools matching AGP 9.1.1. Easiest path is to install [Android Studio](https://developer.android.com/studio) — it bundles the SDK manager, command-line tools, and adb. After install, open Android Studio's SDK Manager and install:
  - Android SDK Platform 36 (Android 16)
  - Android SDK Build-Tools 36.0.0+
  - Android SDK Platform-Tools (for `adb`)
- **`local.properties`** in `android-app/` pointing Gradle at the SDK. Create it manually (it's gitignored):
  ```properties
  sdk.dir=/home/<user>/Android/Sdk
  ```
  (Android Studio writes this file automatically the first time you open the project.)
- **`secrets.properties`** in `android-app/` (NOT the repo root — see `android-app/secrets.properties.example`). Holds the optional dev-channel Brave key; production builds use BYOK and don't need it.
- **Pixel 7** with USB or wireless debugging enabled if you want to install on device. Wireless adb pairing instructions are in [CLAUDE.md](CLAUDE.md) under "Build & run".

### Build commands

```bash
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug                       # requires connected Pixel 7

# Dev iteration without loading the real Gemma model (uses StubInferenceEngine):
./gradlew :androidApp:installDebug -PuseStubEngine=true
```

The first invocation of `./gradlew` downloads Gradle 9.3.1 (~150 MB) into `~/.gradle/wrapper/dists/`. The toolchain is Gradle 9.3.1 + AGP 9.1.1 + Kotlin 2.3.21 + KSP 2.3.7 + Hilt 2.59.2.

### Classifier training pipeline

The classifier-training Python project lives under `classifier-training/` (separate venv from the Android app):

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"                                  # gen, review, dedup, stats CLIs
pip install -e ".[training]"                             # adds torch/transformers/litert-torch (CUDA)
pytest                                                   # schema validation + regression-check tests
```

Generation defaults to local Ollama (`qwen3.5:9b`, zero marginal cost); set `CT_GEN_BACKEND=claude` + `ANTHROPIC_API_KEY` for the optional Claude path. Full training + eval + LiteRT export reproduces the shipped v1.0 artifact in ~10 min on an RTX 5090 — see [CLAUDE.md](CLAUDE.md) under "Classifier build & run" for the command sequence.

### Sideloading the Gemma model

To skip the 5–10 minute download during dev iteration, push the model to the app's internal storage:

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell run-as com.contextsolutions.mobileagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'
```

### Hosted CI

- `.github/workflows/regression-gate.yml` runs `ct-regression-check --skip-eval` on PRs touching `models/`, `datasets/`, or `classifier-training/`. `workflow_dispatch` with `full_eval: true` runs the full classifier eval.
- `.github/workflows/prompt-eval-gate.yml` runs the 15-query `CanonicalEvalTest` on PRs touching `SYSTEM_PROMPT.md`, the prompt assembler, or routing-layer code.

See `docs/SPIKE_RUNBOOK.md` for instructions on running the M0 inference benchmark on a Pixel 7.

## Privacy

User conversations and memories never leave the device. Only Brave Search queries (and optional opt-in aggregate counters routed through Firebase Analytics, default OFF) generate outbound traffic. The telemetry pipeline reads only aggregate tables, has a memory-exclusion canary test, and routes Crashlytics non-fatals through a `SafeCrashReporter` facade with `ContentRedactor` scrubbing. See [PRD.md](PRD.md) §4.4, [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md), and [docs/DATA_SAFETY_NOTES.md](docs/DATA_SAFETY_NOTES.md).
