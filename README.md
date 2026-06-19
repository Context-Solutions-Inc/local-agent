# Local Agent — On-Device AI Assistant (Mobile + Desktop)

A privacy-first AI assistant whose language model runs **entirely on your own device** — phone or
desktop — instead of in someone else's cloud.

> **Documentation map:** this README is the orientation. Build details live in
> [docs/BUILD.md](docs/BUILD.md), how to use the app in [docs/USER_GUIDE.md](docs/USER_GUIDE.md),
> security in [SECURITY.md](SECURITY.md) / [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md), and how to
> contribute in [CONTRIBUTING.md](CONTRIBUTING.md). The full table is at the [bottom](#documentation).

---

## The problem

Cloud assistants are convenient but they send everything you type — your questions, your notes, the
photos you ask about — to a remote server you don't control. For anyone who wants an assistant but
not the data exhaust, the trade-off has been: privacy *or* capability.

**Local Agent removes that trade-off.** It's a chat assistant — with web search, long-term memory,
clock/alarms, voice in/out, and image understanding — where:

- **The model runs on-device.** Inference happens on your phone's GPU/CPU or your laptop. Prompts,
  replies, and the photos you attach never leave the machine to reach an LLM.
- **Your conversations and memories stay local.** They're stored in an on-device database, not synced
  to a vendor.
- **The only required outbound call is a web search.** When a question genuinely needs fresh
  information (weather, scores, news, a stock price), the app sends *just the search query* to the
  [Brave Search API](https://brave.com/search/api/) — never the conversation. Search is opt-in and
  can be turned off entirely, in which case the app is fully offline.

Everything else — deciding *whether* a question needs the web, classifying it, remembering facts
about you, embedding text for retrieval — is done by small models running locally.

See [PRD.md](PRD.md) for the full product specification.

## How it's implemented

One **Kotlin Multiplatform** codebase ships the same agent on Android and on the desktop (Linux,
macOS, Windows), sharing both the core logic and the UI:

- **`:shared`** — the platform-agnostic agent core (agent loop, pre-flight classifier, search,
  memory, prompt assembly, jobs, telemetry) in `commonMain`, with `androidMain` / `desktopMain`
  actuals for the platform-specific pieces.
- **`:ui`** — a **Compose Multiplatform** UI shared by both platforms: every screen, including Chat.
- **`:androidApp` / `:desktopApp`** — thin shells that wire the shared core + UI to each platform.
- **`:desktopHarness`** — a headless CLI that drives the real agent loop for testing.

Dependency injection is **Koin** across all modules (Hilt was fully removed). The LLM runtime differs
per platform but hides behind a single `InferenceEngine` seam:

| Concern | Android | Desktop |
|---|---|---|
| **Chat LLM** | Gemma 4 E2B via **LiteRT-LM** (`com.google.ai.edge.litertlm`, GPU on Mali-G710, CPU fallback) | Gemma 4 E2B **GGUF** (Q4_K_M) via a llama.cpp **`llama-server`** localhost subprocess (Vulkan/Metal, CPU fallback) |
| **Vision (image input)** | Gemma's vision tower baked into the `.litertlm` model (enabled via LiteRT-LM `visionBackend`) | Separate `gemma-4-E2B-it-mmproj-F16.gguf` multimodal projector loaded by `llama-server --mmproj` (native `mtmd`) |
| **Pre-flight classifier + embedder** | LiteRT (`com.google.ai.edge.litert`, INT8 `.tflite`) | ONNX Runtime (`.onnx`) |
| **Voice (STT + read-aloud)** | STT: Android `SpeechRecognizer`; TTS: `android.speech.tts.TextToSpeech` (OS voice) | STT: Vosk (offline); TTS: per-OS engine + bundled offline **Piper** neural TTS |
| **Secrets at rest** | Android Keystore / `EncryptedSharedPreferences` | PKCS#12 `secrets.p12` |

Two **optional** paths sit behind the same `InferenceEngine` seam, off by default:

- **Remote LLM** — point the *chat* model at a remote **Ollama** or OpenAI-compatible server (the
  on-device classifier/embedder/search/memory stay local regardless).
- **Anywhere access** — a paid, end-to-end-encrypted relay (Secure Gateway) so your phone can reach
  your desktop agent from outside the LAN.

**Toolchain:** JDK 17 · Gradle 9.3.1 · AGP 9.1.1 · Kotlin 2.3.21 · Compose Multiplatform 1.10.3 ·
Koin 4.2.1 · Ktor 3.0.2 · SQLDelight 2.0.2. Android targets API 36 (Android 16); Pixel 7 is the
Phase-1 reference device.

## Design overview

Every chat message runs through one `AgentLoop` (`shared/.../agent/AgentLoop.kt`). Crucially, **the
LLM is never given callable tools** — all deterministic behaviour (clock, my-list, memory,
weather/finance cards, `run job`) is dispatched *before* the model, and web results are injected as
context *around* it. The flow:

```
user turn
   │
   ├─▶ deterministic short-circuits  (image? clock? weather? finance? run-job? my-list? remember/forget?)
   │      └─ handled directly, render a card or list — LLM may be skipped entirely
   │
   ├─▶ memory retrieval              (MiniLM embedder → cosine over on-device memory store)
   │
   ├─▶ PreflightRouter               (DistilBERT 3-head classifier → search? 3-band decision;
   │                                   + temporal & explicit "web search …" force-fires)
   │      └─ if search fires:
   │            VerticalSearchDispatcher  → Brave  /web/search (FINANCE)  or  /llm/context (GENERAL/NEWS/SPORTS)
   │            WEATHER → national RSS/DWML feed, rendered deterministically (never sent to the LLM)
   │
   ├─▶ PromptAssembler               (system instruction + memory block; search results ride the
   │                                   current user turn; history scoped & greedy decode on RAG turns)
   │
   ├─▶ InferenceEngine.generate()    (RoutingInferenceEngine decides backend once per load:
   │                                   remote Ollama/relay if active+reachable, else on-device LiteRT-LM / llama-server)
   │
   └─▶ post-turn memory extraction   (after the answer, the on-device memory classifier scans the user
                                       turn; above threshold it proposes a fact via a Save/Dismiss consent
                                       card — nothing is stored without explicit consent. Skipped on
                                       deterministic turns via Done.skipMemoryExtraction)
```

For deeper design references see [SYSTEM_PROMPT.md](SYSTEM_PROMPT.md) (prompt construction),
[docs/DESKTOP_PORT_PLAN.md](docs/DESKTOP_PORT_PLAN.md) (the desktop architecture), and
[CLAUDE.md](CLAUDE.md) (the numbered hard-invariants that the code relies on).

## Quick start

Full instructions — prerequisites, Docker dev container, flags, model setup, packaging — are in
**[docs/BUILD.md](docs/BUILD.md)**. The minimum:

**Prerequisites:** JDK 17. For Android also the Android SDK (platform 36) and a Pixel 7. The desktop
build needs only JDK 17.

```bash
git clone --recurse-submodules <repo-url>      # agent-jobs is a submodule
cd local-agent/android-app          # the Gradle project lives here
```

**Models download automatically on first run** — the small pre-flight classifier + MiniLM embedder
from the CDN, and the Gemma LLM from Hugging Face — so a fresh checkout needs nothing extra. To
pre-stage them and skip the first-run downloads (e.g. for offline dev or fast device installs), see
[docs/BUILD.md](docs/BUILD.md) "Sideloading models".

**Android** (needs `android-app/secrets.properties` — copy `secrets.properties.example` and fill in a
[Brave key](https://brave.com/search/api/) + [HF token](https://huggingface.co/); the device build fails fast without it). Use `adb` from the [Android SDK](https://developer.android.com/tools/releases/platform-tools) to connect to your device.

```bash
adb devices
List of devices attached
192.168.1.93:38863	device

./gradlew :androidApp:installDebug                   # build + install on a connected Pixel 7
```

**Desktop** (no Android SDK required):

```bash
./gradlew :desktopApp:run                             # opens the chat window + system tray
DI_CHECK=1 ./gradlew :desktopApp:run                  # headless: resolve the Koin graph and exit
```

**Desktop with Secure Gateway** (no Android SDK required):

```bash
LOCALAGENT_GATEWAY_URL=http://192.168.1.103:8080 \
LOCALAGENT_RELAY_WS_URL=ws://192.168.1.103:8443/v1/connect \
./gradlew :desktopApp:run                           # opens the chat window + system tray
```

On first run each platform fetches its models from the CDN / Hugging Face: the pre-flight classifier
+ MiniLM embedder on both, plus the Gemma LLM (`.litertlm` on Android via the in-app download gate;
GGUF + `llama-server` binary on desktop). Both platforms then run fully offline once the models are
present (search just stays off until a Brave key is set). See [docs/BUILD.md](docs/BUILD.md) for
sideloading models to skip downloads.

## Status

**M0–M6 complete** — an internal-quality build ready for closed beta. End-to-end chat with web
search, the on-device pre-flight classifier, the memory subsystem, voice I/O, vision, opt-in
telemetry, and first-run onboarding all run today. The desktop port (Compose Multiplatform + Koin +
llama.cpp) and the optional remote-LLM / anywhere-access paths are merged to `main`. M7 (closed beta
→ Play Store) is not started. Milestone history is in [PHASE1_PLAN.md](PHASE1_PLAN.md) §5.

## Documentation

| Document | What it covers |
|---|---|
| [docs/BUILD.md](docs/BUILD.md) | **Build book** — prerequisites, toolchain, build flags, Docker dev container, classifier training, CI, day-to-day running |
| [docs/PRODUCTION_DESKTOP_RUNBOOK.md](docs/PRODUCTION_DESKTOP_RUNBOOK.md) | **Run book** — end-to-end signed/notarized production installer procedure per OS |
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | **User guide** — configuring search sources, forcing a web search, photo input, voice in/read-aloud, desktop appearance |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contributor guidelines, conventions, the hard-invariant model, testing |
| [SECURITY.md](SECURITY.md) · [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) | Vulnerability reporting policy · full threat analysis (trust boundaries, egress, data-at-rest) |
| [PRD.md](PRD.md) · [PHASE1_PLAN.md](PHASE1_PLAN.md) · [SYSTEM_PROMPT.md](SYSTEM_PROMPT.md) | Product spec · milestone plan · prompt construction spec |
| [CLAUDE.md](CLAUDE.md) | The numbered hard invariants the code enforces (read before changing architecture) |
| [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) · [docs/DATA_SAFETY_NOTES.md](docs/DATA_SAFETY_NOTES.md) | User-facing privacy policy · Play Data Safety mapping |
| [docs/](docs/) | Decision memos (M0), per-milestone plans (M3–M7), handoffs, model cards |

## License

Licensed under the **MIT License** — see [LICENSE](LICENSE). © 2026 Context Solutions Inc.
