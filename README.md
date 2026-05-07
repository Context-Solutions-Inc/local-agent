# Mobile Agent — On-Device AI Assistant (Android)

Privacy-first on-device assistant running Gemma 4 E2B locally on Android, with Brave Search as the only outbound network dependency. See [PRD.md](PRD.md) for the full product spec and [PHASE1_PLAN.md](PHASE1_PLAN.md) for the implementation plan.

## Repository layout

```
.
├── PRD.md                          Product requirements
├── SYSTEM_PROMPT.md                System prompt construction spec
├── CLASSIFIER_DATASETS.md          Classifier dataset specifications
├── PHASE1_PLAN.md                  Phase 1 implementation plan
├── agent_loop_state_diagram.svg    Agent loop reference diagram
│
├── android-app/                    KMP shared module + Android Compose shell
│   ├── shared/                     commonMain (agent loop, prompt, search, storage)
│   │                               androidMain (LiteRT-LM JNI, OkHttp engine)
│   │                               iosMain (stubbed for Phase 1)
│   └── androidApp/                 Compose UI, Hilt DI, spike harness
│
├── classifier-training/            Python ML — dataset gen, fine-tuning, quantization
│   └── src/classifier_training/    Pydantic schemas, generation, labeling, training
│
├── datasets/                       JSONL files (payloads gitignored, manifests committed)
│   ├── preflight/                  Pre-flight search classifier dataset
│   └── memory/                     Memory extraction classifier dataset
│
├── eval/                           Regression harnesses, canonical query suites
│
└── docs/                           M0 decision memo, spike runbook, decision logs
```

## Targets

- **Devices:** Google Pixel 7 (Phase 1)
- **OS:** Android 16 (API 36)+
- **Language stack:** Kotlin 2.x, Jetpack Compose, Kotlin Multiplatform
- **Inference runtime:** LiteRT-LM (Android JNI)
- **Models:** Gemma 4 E2B (primary, ~2.58 GB), MobileBERT-class classifiers, MiniLM-L6-v2 embedder

## Status

**M0 + M1 + M2 complete.** End-to-end chat with web search runs on Pixel 7 today.

| Milestone | State |
|---|---|
| M0 Foundation & spike | ✅ Complete 2026-05-05. Gemma 4 E2B on GPU validated on Pixel 7: first token p50 = 0.55 s, sustained 13.5 tok/s mean, peak PSS 3.52 GB. See `docs/M0_DECISION_MEMO.md`. |
| M1 WS-1 Inference foundation | ✅ Complete 2026-05-05. `LiteRtInferenceEngine` w/ CPU fallback + active-accelerator surfacing; `InferenceSessionManager` w/ 5-min idle unload + FGS lifecycle + `onTrimMemory` force-unload; resumable WorkManager-driven model download w/ SHA-256 verification; all 12 exit-gate drills passed. |
| M2 Web search & agent loop (incl. M1 WS-2/3/11) | ✅ Complete 2026-05-07. End-to-end chat with web search via Gemma's tool calls. Brave Search client + LRU cache; ReAct loop on a single LiteRT-LM `Conversation` with structured tool registration (`ConversationConfig.tools` + `Content.ToolResponse`); Compose chat UI with bubbles, streaming, citation chips with browser deep-links, search-status chip; settings screen with BYOK key entry, search toggle, cache clear. 107 unit tests. |
| WS-5/6 datasets | Schemas + generation prompts drafted; not yet built |

## Building

### Prerequisites

- **JDK 17** (Temurin recommended). Set `JAVA_HOME` if not already on `PATH`.
- **Android SDK** with platform `android-36` and build-tools matching AGP 8.7.x. Easiest path is to install [Android Studio](https://developer.android.com/studio) — it bundles the SDK manager, command-line tools, and adb. After install, open Android Studio's SDK Manager and install:
  - Android SDK Platform 36 (Android 16)
  - Android SDK Build-Tools 35.0.0+
  - Android SDK Platform-Tools (for `adb`)
- **`local.properties`** in `android-app/` pointing Gradle at the SDK. Create it manually (it's gitignored):
  ```properties
  sdk.dir=/home/lawrenceley/Android/Sdk
  ```
  (Android Studio writes this file automatically the first time you open the project.)
- **Pixel 7** with USB debugging enabled if you want to install on device.

### Build commands

```bash
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug      # requires connected Pixel 7 (or other Android 16 device)
```

The first invocation of `./gradlew` downloads Gradle 8.11.1 (~150 MB) into `~/.gradle/wrapper/dists/`.

The classifier-training Python project lives under `classifier-training/`:

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest                                  # runs schema validation tests
```

See `docs/SPIKE_RUNBOOK.md` for instructions on running the M0 inference benchmark on a Pixel 7.

## Privacy

User conversations and memories never leave the device. Only Brave Search queries (and optional opt-in classifier-improvement aggregates) generate outbound traffic. See PRD section 4.4.
