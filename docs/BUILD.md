# Build Book

How to build, run, and iterate on Local Agent — Android and desktop — plus the classifier-training
pipeline and CI. For *using* the app see [USER_GUIDE.md](USER_GUIDE.md); for production signed
installers see the run book, [PRODUCTION_DESKTOP_RUNBOOK.md](PRODUCTION_DESKTOP_RUNBOOK.md).

> The Gradle project lives under **`android-app/`**, not the repo root. All `./gradlew` commands below
> are run from `android-app/`.

## Contents

- [Toolchain](#toolchain)
- [Prerequisites](#prerequisites)
- [Building & running Android](#building--running-android)
- [Building & running desktop](#building--running-desktop)
- [Build flags & env vars](#build-flags--env-vars)
- [Sideloading models (fast dev installs)](#sideloading-models-fast-dev-installs)
- [Docker dev container](#docker-dev-container)
- [Classifier-training pipeline](#classifier-training-pipeline)
- [Hosted CI](#hosted-ci)
- [Running day-to-day](#running-day-to-day)

---

## Toolchain

The first `./gradlew` invocation downloads Gradle 9.3.1 (~150 MB) into `~/.gradle/wrapper/dists/`.

| Component | Version |
|---|---|
| JDK | 17 (Temurin recommended) |
| Gradle | 9.3.1 (via wrapper) |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.3.21 |
| Compose Multiplatform | 1.10.3 |
| Koin (DI) | 4.2.1 |
| Ktor | 3.0.2 |
| SQLDelight | 2.0.2 |
| Android SDK | compile/target/min = API 36 (Android 16) |
| LiteRT-LM (Android LLM) | 0.12.0 |
| ai-edge-litert (Android classifier/embedder) | 2.1.5 |
| ONNX Runtime (desktop classifier/embedder) | 1.20.0 |
| llama.cpp `llama-server` (desktop LLM) | pinned tag `b9478` (`LlamaServerBinaryStore`) |

DI is **Koin** across `:shared` / `:androidApp` / `:desktopApp` — Hilt and KSP were fully removed.
Versions are pinned in `android-app/gradle/libs.versions.toml`.

### Gradle modules

| Module | Role |
|---|---|
| `:shared` | KMP agent core (commonMain) + platform actuals (androidMain/desktopMain) |
| `:ui` | Compose Multiplatform UI shared by both shells (every screen incl. Chat) |
| `:androidApp` | Thin Android shell (MainActivity, Koin start, WorkManager download, services, spike harness) |
| `:desktopApp` | Thin Compose Desktop shell (window + tray + warm model + jpackage installers) |
| `:desktopHarness` | Headless CLI driving the real `AgentLoop` on llama.cpp (no UI/DI) |

## Prerequisites

- **JDK 17.** Set `JAVA_HOME` if it isn't on `PATH`.
- **Android SDK** (Android builds only) with platform `android-36` and build-tools matching AGP 9.1.1.
  Installing [Android Studio](https://developer.android.com/studio) is the easiest path (bundles the
  SDK manager, command-line tools, and adb). Install: SDK Platform 36, Build-Tools 36.0.0+,
  Platform-Tools (`adb`).
- **`local.properties`** in `android-app/` pointing at the SDK (`sdk.dir=/home/<user>/Android/Sdk`) —
  *or* an `ANDROID_HOME` env var. Android Studio writes `local.properties` automatically; otherwise
  create it (it's gitignored). If you also use the Docker container, prefer `ANDROID_HOME` (a host-path
  `sdk.dir=` is wrong inside the container).
- **`secrets.properties`** in `android-app/` (NOT the repo root — see `secrets.properties.example`).
  Holds the dev-channel Brave key, the HuggingFace token, and Gemma checksums. **Required for a debug
  device build:** `assembleDebug` / `installDebug` fail fast at configuration time if it's missing
  (without it the installed app can't search or download the model). Release builds use BYOK and don't
  need it; unit tests and the desktop build don't read it.

  | Key | Purpose |
  |---|---|
  | `BRAVE_DEV_KEY` | Bundled into debug builds only; production users supply their own via Settings |
  | `MODEL_DOWNLOAD_URL` | Gemma 4 E2B `.litertlm` artifact URL (pre-filled in the example) |
  | `MODEL_SHA256` / `MODEL_SIZE_BYTES` | Checksum + size for the download + progress |
  | `HF_AUTH_TOKEN` | Read-scoped HuggingFace token for the gated Gemma repo (accept the license on HF first) |

- **`google-services.json`** in `android-app/androidApp/` — *optional*. Enables Firebase Analytics +
  Crashlytics. Gitignored, so it's absent in fresh checkouts; the app runs fine without it (telemetry
  stays off, no launch crash). Add it (registered to `com.contextsolutions.localagent` /
  `…localagent.debug`) only if you want crash/analytics reporting.
- **Submodules:** clone with `--recurse-submodules`, or run `git submodule update --init` (pulls
  `agent-jobs`, the desktop job library). The `secure-gateway` SDK is a *sibling* repo published to
  `mavenLocal()`, not a submodule — only needed for the relay/subscription paths (see the run book).
- **A Pixel 7** with USB or wireless debugging, to install on device. Wireless adb pairing notes are in
  [CLAUDE.md](../CLAUDE.md) under "Build & run".

## Building & running Android

```bash
cd android-app
./gradlew :androidApp:assembleDebug                  # build the debug APK
./gradlew :androidApp:installDebug                   # build + install (requires a connected Pixel 7)

# Dev iteration without loading the real Gemma model (StubInferenceEngine):
./gradlew :androidApp:installDebug -PuseStubEngine=true
```

Launch:

```bash
adb shell am start -n com.contextsolutions.localagent.debug/com.contextsolutions.localagent.app.MainActivity
```

The APK lands at `android-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Building & running desktop

The desktop app (`:desktopApp`) needs only **JDK 17** — no Android SDK.

```bash
cd android-app
./gradlew :desktopApp:run                             # opens the chat window + system tray

# With the relay/subscription gateway configured (optional, for anywhere-access):
LOCALAGENT_GATEWAY_URL=https://auth.example.com \
LOCALAGENT_RELAY_WS_URL=wss://relay.example.com/v1/connect \
  ./gradlew :desktopApp:run
```

Closing the window **hides to the tray** (the app and model stay resident); quit from the tray menu.

> **Headless / CI smoke test:** `DI_CHECK=1 ./gradlew :desktopApp:run` force-resolves the whole Koin
> graph, prints `Koin agent graph resolved OK`, and exits **without opening a window** — a display-free
> check of the wiring.

### Desktop first-run setup (models + Brave key)

Like Android, the desktop ships **no model and no bundled API key** — the operator supplies them.
Bundled config (vocab, search defaults, locations) rides in the app already; the large artifacts live
in the per-OS app-data dir:

- Linux: `~/.local/share/LocalAgent/`
- macOS: `~/Library/Application Support/LocalAgent/`
- Windows: `%LOCALAPPDATA%\LocalAgent\`

| Artifact | How to provide |
|---|---|
| **Gemma 4 E2B GGUF** (Q4_K_M) | Fetched via the tray's download (fill URL + SHA-256 + size in `DesktopModelSpec`), or set `GEMMA_GGUF_PATH=/abs/path.gguf`. Loaded lazily on the first chat turn. |
| **`llama-server` binary** | Auto-downloaded on first run (pinned tag `b9478`); CPU + GPU (Vulkan/Metal) variants. Override with `LOCALAGENT_LLAMA_SERVER_VARIANT` or point `LOCALAGENT_LLAMA_SERVER` at your own build. |
| **ONNX classifier + embedder** | Auto-downloaded on first run from the CDN (default `DesktopAuxModels.DEFAULT_BASE_URL`; override the host with `-PauxModelBaseUrl`). Or drop them in app-data `models/`, or set `LOCALAGENT_CLASSIFIER_ONNX` / `LOCALAGENT_EMBEDDER_ONNX`. sha256 + size are pinned in `DesktopAuxModels`. Without them the classifier no-ops → pre-flight under-fires (the explicit `web search …` command still works). |
| **Brave Search key** | `BRAVE_API_KEY` env var, or store it via the app's SecureStorage. Without it, search is disabled (the model answers offline). |
| **Vosk acoustic model** (optional) | app-data `models/vosk`, or `LOCALAGENT_VOSK_MODEL`. Enables dictation; STT silently no-ops without it. |

### Packaging native installers

jpackage builds **only for the host OS**, so each platform produces its own installers (the CI matrix
in `.github/workflows/desktop-package.yml` runs one job per OS):

```bash
./gradlew :desktopApp:createDistributable              # runnable app-image (no OS packaging tool needed)
./gradlew :desktopApp:packageDistributionForCurrentOS  # every installer for the host OS (.deb/.rpm, .dmg/.pkg, .msi/.exe)
./gradlew :desktopApp:packageDeb                        # a single Linux format
```

`createDistributable` is the local smoke test (no system packaging tool needed); the full installers
need the host's packaging tools (`fakeroot`/`rpm` on Linux, WiX on Windows, etc.). The LLM's
`llama-server` always has a **CPU** variant ("always works") and **GPU offload is automatic** — a
Vulkan (Linux/Windows) / Metal (macOS) variant downloads by default and falls back to CPU when no
driver is present. Full native-runtime / GPU / distribution detail is in
[DESKTOP_PACKAGING.md](DESKTOP_PACKAGING.md); the end-to-end **signed + notarized** production
procedure is the run book, [PRODUCTION_DESKTOP_RUNBOOK.md](PRODUCTION_DESKTOP_RUNBOOK.md).

## Build flags & env vars

Gradle properties (`-Pname=value`):

| Flag | Module | Effect |
|---|---|---|
| `-PuseStubEngine=true` | `:androidApp` | Swap the LLM for `StubInferenceEngine` (canned tokens) — dev without loading Gemma. Default false. |
| `-PonnxGpu=true` | `:shared` (desktop) | Swap ONNX CPU natives for the CUDA EP build for the desktop classifier/embedder. Default CPU. |
| `-PauxModelBaseUrl=https://host/path` | `:desktopApp` | Override the compile-time hosting endpoint for the ONNX aux models (baked into `desktop_build_info.properties`). Default is the CDN (`DesktopAuxModels.DEFAULT_BASE_URL`). |

Runtime env vars (desktop): `LOCALAGENT_GATEWAY_URL`, `LOCALAGENT_RELAY_WS_URL` (relay/subscription),
`LOCALAGENT_LLAMA_SERVER_VARIANT` / `LOCALAGENT_LLAMA_SERVER`, `GEMMA_GGUF_PATH`,
`LOCALAGENT_CLASSIFIER_ONNX` / `LOCALAGENT_EMBEDDER_ONNX`, `LOCALAGENT_VOSK_MODEL`, `BRAVE_API_KEY`,
`LOCALAGENT_HEADLESS=1` (background service, no window), `LOCALAGENT_KEYSTORE_PASSWORD` (overrides the
derived desktop secret-store password). `DI_CHECK=1` resolves the Koin graph and exits.

## Sideloading models (fast dev installs)

### Gemma (Android)

Skip the 5–10 minute first-run download by pushing the model into app-internal storage:

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/
adb shell "run-as com.contextsolutions.localagent.debug \
  sh -c 'mkdir -p files/models && cp /data/local/tmp/gemma-4-E2B-it.litertlm files/models/'"
```

### Classifier + embedder (Android)

The classifier (`preflight_memory_shared_v1.0.0_int8.tflite`, ~67 MB) and embedder
(`all-MiniLM-L6-v2_int8.tflite`, ~23 MB) are **not** bundled (PR #3) — they download from the CDN on
first run, folded into the same `DownloadScreen` gate as the Gemma LLM, into `filesDir/models/`. The
engines load from there only. To skip the download during dev, fetch them from the CDN once and
pre-stage them onto the device:

```bash
# Fetch into a repo-root models/ dir (gitignored).
mkdir -p models
base=https://pub-f6c21df457bd434ebe799585697ff4b6.r2.dev
for f in preflight_memory_shared_v1.0.0_int8.tflite all-MiniLM-L6-v2_int8.tflite; do
  curl -L --output-dir models -O --progress-bar "$base/$f"
done

# Push them into app-internal storage.
cd android-app
PKG=com.contextsolutions.localagent.debug
for f in preflight_memory_shared_v1.0.0_int8.tflite all-MiniLM-L6-v2_int8.tflite; do
  adb push "../models/$f" /data/local/tmp/
  adb shell run-as $PKG cp /data/local/tmp/$f /data/data/$PKG/files/models/$f
  adb shell rm -f /data/local/tmp/$f
done
```

> Copy **one file per `run-as cp`** as shown — a multi-file `cp a b dest/` mis-parses through the
> nested `adb shell` → `run-as` → `sh` quoting.

Confirm it worked (else warm-up fails and the agent silently falls through to plain Gemma):

```bash
adb logcat -d -s ClassifierEngine:* EmbedderEngine:*
# loading classifier from filesDir (…)        ← picked up the pushed model
# GPU init failed; falling back to CPU XNNPACK ← normal (the GPU delegate refuses these graphs)
# classifier loaded on CPU                     ← success
```

## Docker dev container

A `Dockerfile` + `docker-compose.yml` at the repo root provide a self-contained Linux dev environment
with JDK 17, the Android SDK (platform 36, build-tools 36.0.0, platform-tools), Node 22, the Claude
Code CLI, the GitHub CLI (`gh`), and **Python 3.11 + the full `classifier-training` stack** — all
pinned to versions known to work together. Use it when you'd rather not install the Android SDK or the
Python ML stack on the host, or when running headless.

The Python deps are baked into a venv at `/opt/venv` (on `PATH`), and `classifier-training` is
installed **editable** against the bind-mounted source — so the `ct-*` CLIs run against your live tree.

**What's NOT included:** adb-to-device passthrough (build the APK in the container, then `adb install`
from the host). iOS / Kotlin-Native compile fails on Linux (Phase-1 limitation), so `./gradlew check`
needs `-x :shared:compileKotlinIosX64 -x :shared:compileKotlinIosSimulatorArm64` to go green in the
container.

**One-time build** (~15 min; the `[training]` extra pulls CUDA PyTorch so the image is ~34 GB):

```bash
docker compose build
```

**Common workflows** (each is a one-shot `run --rm`):

```bash
docker compose run --rm dev                                       # interactive shell
docker compose run --rm dev ./gradlew :androidApp:assembleDebug   # build the debug APK
docker compose run --rm dev ./gradlew test                        # JVM unit tests
docker compose run --rm dev ct-stats                              # classifier-training CLI
docker compose run --rm dev python -m pytest classifier-training  # classifier-training tests
```

The APK lands on the host at `android-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
(bind-mounted). From the host: `adb install -r <that path>`.

**GitHub CLI auth:** the host keeps its token in the system keyring, so export it first —
`export GH_TOKEN=$(gh auth token)` — and compose forwards it.

**Caveats:** the container runs as user `dev` (UID/GID 1000); if your host UID differs, rebuild with
`--build-arg USER_UID=$(id -u) --build-arg USER_GID=$(id -g)` and `docker compose down -v`. If
`android-app/local.properties` pins `sdk.dir=` to a host path the container can't see it — remove that
line (Gradle falls back to `ANDROID_HOME`). `ANTHROPIC_API_KEY` / `GH_TOKEN` pass through if set.

## Classifier-training pipeline

The classifier-training Python project lives under `classifier-training/` (separate venv from the
Android app):

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"        # gen, review, dedup, stats CLIs
pip install -e ".[training]"   # adds torch/transformers/litert-torch (CUDA)
pytest                         # schema validation + regression-check tests
```

Generation defaults to local Ollama (`qwen3.5:9b`, zero marginal cost); set `CT_GEN_BACKEND=claude` +
`ANTHROPIC_API_KEY` for the optional Claude path. Reproducing the shipped v1.0 artifact
(`ct-train-classifier` → `ct-eval-classifier` → `ct-export-litert`) takes ~10 min on an RTX 5090. The
full CLI list is in `classifier-training/pyproject.toml`; the command sequence is in
[CLAUDE.md](../CLAUDE.md).

WS-14 regression gate (run before any new `.tflite` lands in `models/`) — exit 0 PASS / 1 SHA-mismatch
/ 2 regression / 3 infra:

```bash
ct-regression-check --ckpt ../eval/runs/<ts>/best.pt
ct-regression-check --skip-eval --ckpt path/to/metrics.json   # hosted-CI flow
```

## Hosted CI

- `.github/workflows/regression-gate.yml` — runs `ct-regression-check --skip-eval` on PRs touching
  `models/`, `datasets/`, or `classifier-training/`. `workflow_dispatch` with `full_eval: true` runs
  the full classifier eval.
- `.github/workflows/prompt-eval-gate.yml` — runs the 15-query `CanonicalEvalTest` on PRs touching
  `SYSTEM_PROMPT.md`, the prompt assembler, or routing-layer code.
- `.github/workflows/desktop-package.yml` — per-OS jpackage matrix.

See [SPIKE_RUNBOOK.md](SPIKE_RUNBOOK.md) for running the M0 inference benchmark on a Pixel 7.

## Running day-to-day

| Goal | Command |
|---|---|
| Install on a Pixel 7 | `./gradlew :androidApp:installDebug` then launch from the app drawer (or `adb shell am start -n com.contextsolutions.localagent.debug/com.contextsolutions.localagent.app.MainActivity`) |
| Run the desktop app | `./gradlew :desktopApp:run` (window + tray) |
| Desktop background service (no window) | `LOCALAGENT_HEADLESS=1 ./gradlew :desktopApp:run` — see service templates in `desktopApp/packaging/` and [DESKTOP_PACKAGING.md](DESKTOP_PACKAGING.md) "Headless / standalone deployment" |
| Headless wiring check | `DI_CHECK=1 ./gradlew :desktopApp:run` |
| Build a production installer | Follow the run book → [PRODUCTION_DESKTOP_RUNBOOK.md](PRODUCTION_DESKTOP_RUNBOOK.md) |

For installed desktop builds, the launcher/binary is produced by `createDistributable`
(`:desktopApp/build/compose/binaries/`) or the per-OS installer from
`packageDistributionForCurrentOS`. App data (models, DB, secrets) lives in the per-OS app-data dir
listed under [Desktop first-run setup](#desktop-first-run-setup-models--brave-key).
