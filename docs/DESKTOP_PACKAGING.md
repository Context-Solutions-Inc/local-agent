# Desktop packaging & native-runtime strategy (v0.1.0)

How the Compose Desktop app (`:desktopApp`) is built into native installers, how its
native runtimes (llama.cpp / ONNX Runtime / Vosk) are shipped, and what the user's
machine must provide. Companion to `docs/DESKTOP_PORT_PLAN.md` Phase 8.

## TL;DR

- **One installer per OS, built on that OS.** jpackage cross-builds nothing —
  `.deb`/`.rpm` on Linux, `.dmg`/`.pkg` on macOS, `.msi`/`.exe` on Windows. The
  `.github/workflows/desktop-package.yml` matrix produces all three.
- **CPU-default build that ALWAYS works.** The bundled llama.cpp + ONNX Runtime +
  Vosk natives are CPU-only. No GPU, driver, or toolkit is required to run.
- **GPU is opt-in and additive.** It needs the user's own vendor driver; we never
  ship the CUDA toolkit. See [GPU acceleration](#gpu-acceleration).
- **Models are NOT in the installer.** The multi-GB GGUF + the ONNX classifier/
  embedder + the Vosk acoustic model download into the app-data dir at first run.

## Build commands

From `android-app/`:

```bash
./gradlew :desktopApp:createDistributable              # jpackage app-image (no OS pkg tool)
./gradlew :desktopApp:packageDistributionForCurrentOS  # every installer for the host OS
./gradlew :desktopApp:packageDeb                       # one format (Linux)
```

`createDistributable` builds the runnable app-image (jlink runtime + launcher +
icon) using only the JDK's `jpackage` — no `fakeroot`/`rpmbuild`/`productbuild`.
It's the CI/dev smoke gate for the packaging config. The `package*` tasks wrap that
image in an OS installer and need the host packaging tools (below).

### Host toolchain per OS

| OS | Formats | Extra tools (beyond the JDK's jpackage) |
|---|---|---|
| Linux | `.deb`, `.rpm` | `fakeroot` (deb), `rpm`/`rpmbuild` (rpm) — `sudo apt-get install -y fakeroot rpm` |
| macOS | `.dmg`, `.pkg` | none for unsigned local builds (signing/notarisation deferred) |
| Windows | `.msi`, `.exe` | WiX Toolset for `.msi` (preinstalled on `windows-latest`) |

The CI matrix installs the Linux tools; macOS/Windows runners ship theirs.

### Distribution config

`desktopApp/build.gradle.kts` `nativeDistributions`:
- **Metadata:** vendor "Context Solutions", description, copyright, `packageVersion`
  `1.0.0` — the installer/upgrade-detection version, **decoupled** from the release
  tag (v0.1.0): the Compose plugin + jpackage require MAJOR > 0, so a 0.x installer
  version is rejected. The user-facing version (About dialog, `appVersionName`) is
  v0.1.0.
- **`includeAllModules = true`** — ships the full JDK runtime image instead of a
  hand-curated jlink module list. The app needs modules easy to miss when probing
  (`java.sql` for the SQLite JDBC driver, `jdk.management` for the OS free-RAM
  headroom probe, `java.desktop` for the Swing file chooser + `javax.sound`
  capture, `jdk.crypto.ec` for TLS to Brave). The extra runtime weight (~tens of
  MB) is negligible against the multi-GB model downloaded at first run, and it
  removes a whole class of "works on dev, `NoClassDefFound` in the installer" bugs.
- **`jvmArgs = -Xmx2g, -Dfile.encoding=UTF-8`.** The GGUF model and the ONNX graphs
  live in **native, off-heap** memory (llama.cpp / ONNX Runtime own their buffers),
  so the JVM heap stays small — 2 GB covers the Compose UI, image preprocessing, and
  ONNX tensor marshalling with headroom. Raise only if an on-heap path is added.
- **Per-OS:** Linux deb maintainer + `Utility` menu/category; macOS `bundleID`
  `com.contextsolutions.mobileagent` + `public.app-category.productivity`; Windows
  a **stable `upgradeUuid`** (`b6c3f2a4-…-4f33` — MSI keys upgrades off it; never
  regenerate once shipped), per-user install, dir chooser.
- **Icons:** one brand master rendered by `desktopApp/icons/generate_icons.py`
  (Pillow) → `icon.png` (Linux + the in-app tray/window resource), `icon.ico`
  (Windows), `icon.icns` (macOS), all committed so the build needs no image tooling.

## Native-runtime strategy

Three runtimes carry native code. All ship **CPU-only** natives bundled inside their
JARs and extracted at first use — the always-works baseline.

### llama.cpp (LLM)

- Binding: `net.ladenthin:llama:5.0.1` (bernardladenthin fork, bundles llama.cpp
  b9151 — needed for the `gemma4` GGUF architecture; `de.kherud:llama` was too old).
- The published artifact's `libjllama` is **CPU-only**. `activeAccelerator` is
  best-effort *intent* derived from `n_gpu_layers`; it cannot read actual offload
  back from the JNI layer, so on the bundled natives it reports the intended backend,
  not a probed one.
- **GPU (CUDA/Metal/Vulkan) is opt-in via a BYO native** — see
  [Enabling LLM GPU offload](#enabling-llm-gpu-offload). The default ships CPU-only;
  the operator builds a GPU-enabled `libjllama` and points the loader at it with
  `-PllamaLibPath`. The engine already requests full offload
  (`InferenceConfig.accelerator` defaults to `AUTO` → `setGpuLayers(999)`), so no
  code change is needed. The *real offload probe* (reading actual layer placement
  back from JNI rather than inferring it) remains **deferred**
  ([Deferred](#deferred-not-in-v010)).

### ONNX Runtime (classifier + embedder)

- `com.microsoft.onnxruntime:onnxruntime:1.20.0` — CPU natives bundled, headless-safe.
- **Optional CUDA EP:** build with `-PonnxGpu=true` to swap in
  `onnxruntime_gpu` (same version). The engines (`OnnxClassifierEngine` /
  `OnnxEmbedderEngine`) already try the CUDA Execution Provider and fall back to CPU
  if `addCUDA` throws, so the GPU jar is a strict superset — a GPU build still runs
  on a CPU-only machine.
- `onnxruntime_gpu` needs the user's **CUDA runtime + cuDNN** matching the ORT 1.20
  CUDA 12.x requirement. We don't bundle them. The classifier/embedder are small and
  fast on CPU (Android p95: classifier 113 ms, embedder 41 ms), so the GPU build is a
  niche optimisation, not the default.

### Vosk (offline STT)

- `com.alphacephei:vosk:0.3.45` — bundles per-OS JNI natives (Linux/macOS/Windows).
- `VoskDictation` degrades to a **no-op** when the acoustic model is absent, so a
  default install has working TTS (OS shell-out) and silently-disabled dictation
  until the model is provided.

## Models & assets — bundled vs downloaded

**Bundled** as `:shared` desktop classpath resources (small, byte-identical to the
Android assets): `vocab.txt`, `preflight_config.json`, `search_defaults.json`,
`locations.json`, `memory_config.json`, `system_memory_config.json`, and the app
icon. These ride in the installer.

**Downloaded at first run** into the app-data dir (too large to bundle, BYO-license,
or operator-supplied):

| Artifact | Source | Notes |
|---|---|---|
| GGUF Gemma (Q4_K_M) | `DesktopModelDownloader` → tray progress | Spec ships blank sha256/size (`isConfigured=false`); operator fills verified coordinates, same BYO policy as Android `secrets.properties`. **Use a standard instruction GGUF with the stock Gemma template (`<start_of_turn>`); avoid "thinking"/"reasoning" community conversions — they carry a `<\|think\|>` block in the chat template and reason out loud in the chat (Android's E2B doesn't). Quick check: `strings model.gguf \| grep -m1 '<\|think\|>'` should find nothing.** |
| ONNX classifier + embedder | app-data `models/` or `MOBILEAGENT_{CLASSIFIER,EMBEDDER}_ONNX` | exported by `ct-export-onnx` / `export_minilm_onnx.py` |
| Vosk acoustic model | app-data `models/vosk` or `MOBILEAGENT_VOSK_MODEL` | optional; STT no-ops without it |

App-data dir per OS:
- Linux: `~/.local/share/MobileAgent/`
- macOS: `~/Library/Application Support/MobileAgent/`
- Windows: `%LOCALAPPDATA%\MobileAgent\`

This keeps the installer small (runtime + app jars, no model) and lets the model be
updated independently of the app.

## GPU acceleration

| Backend | OS | User must provide | How to get it |
|---|---|---|---|
| CUDA (LLM) | Linux/Windows + NVIDIA | NVIDIA driver | BYO `libjllama` + `-PllamaLibPath` (below) |
| Metal (LLM) | macOS (Apple Silicon) | nothing (OS-provided) | BYO `libjllama` + `-PllamaLibPath` (below) |
| Vulkan (LLM) | Linux/Windows | Vulkan-capable driver | BYO `libjllama` + `-PllamaLibPath` (below) |
| CUDA EP (classifier/embedder) | Linux/Windows + NVIDIA | CUDA runtime + cuDNN (CUDA 12.x) | `-PonnxGpu=true` build |

We never ship a vendor driver or the CUDA toolkit — only natives that load against a
driver the user already has. The CPU path is always present as the fallback.

### Enabling LLM GPU offload

The published `net.ladenthin:llama:5.0.1` binding bundles a **CPU-only** `libjllama`,
so out of the box llama.cpp logs `no usable GPU found, --gpu-layers option will be
ignored`. The app's offload request is already wired (`InferenceConfig.accelerator`
defaults to `AUTO`, so `LlamaCppInferenceEngine` calls `setGpuLayers(999)`) — only
the native needs replacing. Two steps:

**1. Build a GPU-enabled `libjllama` for your OS/arch.** Build the binding's JNI
shared lib against llama.cpp **b9151** (the build 5.0.1 bundles — match it so the
JNI ABI lines up) with the GPU backend on, e.g. CUDA:

```bash
# in a checkout of the net.ladenthin/llama fork's JNI sources
cmake -B build -DGGML_CUDA=ON -DCMAKE_BUILD_TYPE=Release   # needs the CUDA toolkit
cmake --build build --config Release -j                     # → a GPU-enabled libjllama.so
```

(Use `-DGGML_VULKAN=ON` for Vulkan, or build on macOS for Metal. The matching vendor
driver — NVIDIA / Vulkan ICD — must already be installed; we don't ship it.)

**2. Point the loader at it with `-PllamaLibPath`** — the path is the **directory
that contains the native**, NOT the `.so`/`.dll`/`.dylib` file itself. The loader
does `Paths.get(libPath, "libjllama.so")`, so pointing at the file makes it look
for `…/libjllama.so/libjllama.so`, fail, and silently fall back to extracting the
bundled CPU native (`Extracted 'libjllama.so' to '/tmp/libjllama.so'`). The lib
name defaults to `jllama` → `libjllama.so`/`.dll`/`.dylib`:

```bash
# point at the FOLDER containing libjllama.so, e.g. the GPU build's resources dir:
./gradlew :desktopApp:run \
  -PllamaLibPath=/abs/path/to/java-llama.cpp/.../net/ladenthin/llama/Linux/x86_64
```

`-PllamaLibPath` injects `-Dnet.ladenthin.llama.lib.path=<dir>` (the binding's
`LlamaLoader` system property) into both `:desktopApp:run` and the packaged app's
`jvmArgs`. On the next model load you'll see `loadModel gpuLayers=999
intendedBackend=CUDA …` and the warning is gone. `activeAccelerator` reports the
intended backend (best-effort — the JNI probe is deferred below).

## Deferred (not in v0.1.0)

- **Shipping a prebuilt GPU `libjllama` + a real `activeAccelerator` probe.** LLM GPU
  offload is now **opt-in** via `-PllamaLibPath` (see [Enabling LLM GPU
  offload](#enabling-llm-gpu-offload)), but the operator still builds the per-OS
  GPU-enabled native themselves — v0.1.0 doesn't ship one (and the installer stays
  CPU-only, always-works). Also deferred: a JNI probe to *confirm* offload rather
  than infer it from `n_gpu_layers`, so `activeAccelerator` stays best-effort intent.
- **Code signing / notarisation.** macOS `.pkg`/`.dmg` and Windows `.msi` are
  unsigned — users will see Gatekeeper / SmartScreen warnings. Signing certs +
  notarisation are a release-hardening follow-up.
- **ProGuard/R8-minified release bundle.** The CI builds the non-minified
  distribution; minifying the reflection/JNI-heavy deps (Koin, sqlite-jdbc, llama/
  ONNX/Vosk bindings) needs a keep-rules pass and is deferred behind the
  always-works guarantee.
