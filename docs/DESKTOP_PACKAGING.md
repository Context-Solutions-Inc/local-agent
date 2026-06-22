# Desktop packaging & native-runtime strategy (v0.1.0)

How the Compose Desktop app (`:desktopApp`) is built into native installers, how its
native runtimes (llama.cpp / ONNX Runtime / Vosk) are shipped, and what the user's
machine must provide. Companion to `docs/DESKTOP_PORT_PLAN.md` Phase 8.

## TL;DR

- **One installer per OS, built on that OS.** jpackage cross-builds nothing —
  `.deb`/`.rpm` on Linux, `.dmg`/`.pkg` on macOS, `.msi`/`.exe` on Windows. The
  `.github/workflows/desktop-package.yml` matrix produces all three.
- **CPU build that ALWAYS works.** The bundled ONNX Runtime + Vosk natives are
  CPU-only, and the LLM's `llama-server` has a CPU variant that always runs. No GPU,
  driver, or toolkit is required.
- **GPU is automatic where a driver exists, never required.** The LLM downloads a
  Vulkan (Linux/Windows) / Metal (macOS) `llama-server` by default and **falls back to
  CPU** if no driver is present — no rebuild, no vendor toolkit shipped. The
  classifier/embedder stay CPU unless you build with `-PonnxGpu=true`. See
  [GPU acceleration](#gpu-acceleration).
- **Models are NOT in the installer.** The multi-GB GGUF + the `llama-server` binary +
  the ONNX classifier/embedder + the Vosk acoustic model download into the app-data dir
  at first run.

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
  `com.contextsolutions.localagent` + `public.app-category.productivity`; Windows
  a **stable `upgradeUuid`** (`b6c3f2a4-…-4f33` — MSI keys upgrades off it; never
  regenerate once shipped), per-user install, dir chooser.
- **Icons:** one brand master rendered by `desktopApp/icons/generate_icons.py`
  (Pillow) → `icon.png` (Linux + the in-app tray/window resource), `icon.ico`
  (Windows), `icon.icns` (macOS), all committed so the build needs no image tooling.

## Native-runtime strategy

Two of the three runtimes (ONNX Runtime, Vosk) ship **CPU-only** natives bundled inside
their JARs and extracted at first use. The LLM is different: it runs as a separate
**`llama-server` subprocess** whose prebuilt binary downloads at first run (CPU + GPU
variants), so it is not a bundled JNI native at all.

### llama.cpp (LLM) — `llama-server` subprocess

- **Not a JNI binding.** The LLM runs in a separate `llama-server` child process
  (`LlamaServerInferenceEngine` + `LlamaServerProcess`), talking HTTP over localhost.
  This replaced the earlier `net.ladenthin:llama` JNI binding — there is **no
  `libjllama`, and no `-PllamaLibPath`** anymore. GPU is chosen by *which prebuilt
  server archive is downloaded*, not by a JVM system property.
- **Prebuilt server, pinned + verified.** `LlamaServerBinaryStore` downloads a
  `llama-server` archive from the GitHub `ggml-org/llama.cpp` releases, pinned to
  `LlamaServerRelease.TAG` (currently `b9478`). Each per-host asset's sha256 + size is
  pinned in `LlamaServerRelease`; the archive is verified, then extracted (via system
  `tar` to preserve `.so` version symlinks) into a per-variant cache dir under the
  app-data `server/` folder.
- **GPU is automatic, CPU is the fallback.** Two variants ship per host: a **CPU**
  archive and a **Vulkan** archive (cross-vendor GPU — Intel/AMD/NVIDIA) on
  Linux/Windows; macOS ships a **single Metal-capable** archive for both (GPU there is
  just `-ngl`). The engine requests the GPU variant by default and **falls back to CPU**
  if the GPU server can't start (no driver) — no operator build step, and we never ship
  a vendor driver. See [GPU acceleration](#gpu-acceleration).
- **Overrides (dev/testing):** `LOCALAGENT_LLAMA_SERVER` points at an existing
  `llama-server` binary (skips the download); `LOCALAGENT_LLAMA_SERVER_VARIANT`
  (`cpu`|`vulkan`|`auto`, default `auto`) forces the variant choice. `activeAccelerator`
  remains best-effort *intent* — a real offload probe is still
  [deferred](#deferred-not-in-v010).

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
  default install has working TTS (OS shell-out, see below) and silently-disabled
  dictation until the model is provided.

### Read-aloud (TTS)

- **Default = OS shell-out** (`DesktopTtsSpeaker`): `say` (macOS) / `spd-say --wait`
  (Linux) / PowerShell `SpeechSynthesizer` (Windows); no-op + log when absent.
  Stopping is engine-specific — Linux also issues `spd-say --cancel` because the
  speech-dispatcher daemon outlives the killed client (invariant #48).
- **Opt-in neural voice = Piper** (PR #66): selecting the "piper" engine in
  Settings → Read-aloud voice downloads a self-contained prebuilt `piper` binary +
  ONNX voice and plays in-JVM via Java Sound (no `aplay`/Python). See the table below.

## Models & assets — bundled vs downloaded

**Bundled** as `:shared` desktop classpath resources (small, byte-identical to the
Android assets): `vocab.txt`, `preflight_config.json`, `search_defaults.json`,
`locations.json`, `memory_config.json`, `system_memory_config.json`, and the app
icon. These ride in the installer.

**Downloaded at first run** into the app-data dir (too large to bundle, BYO-license,
or operator-supplied):

| Artifact | Source | Notes |
|---|---|---|
| `llama-server` binary (CPU + Vulkan/Metal) | `LlamaServerBinaryStore` → GitHub `ggml-org/llama.cpp` release, pinned `LlamaServerRelease.TAG` (`b9478`) | sha256/size pinned per host asset in `LlamaServerRelease`; extracted via system `tar` (preserves `.so` symlinks) into app-data `server/<variant>/`. GPU variant attempted by default, CPU fallback. Override path with `LOCALAGENT_LLAMA_SERVER`, variant with `LOCALAGENT_LLAMA_SERVER_VARIANT` (`cpu`\|`vulkan`\|`auto`) |
| GGUF Gemma (Q4_K_M, ≈3.1 GB) + mmproj-F16 | `DesktopModelDownloader` → tray progress | sha256/size **pinned** in `DesktopModelStore` (`unsloth/gemma-4-E2B-it-GGUF`, public repo — no token unless you point at a gated mirror via `HF_AUTH_TOKEN`). **Use a standard instruction GGUF with the stock Gemma template (`<start_of_turn>`); avoid "thinking"/"reasoning" community conversions — they carry a `<\|think\|>` block in the chat template and reason out loud in the chat (Android's E2B doesn't). Quick check: `strings model.gguf \| grep -m1 '<\|think\|>'` should find nothing.** |
| ONNX classifier + embedder | `DesktopAuxModelStore` → app-data `models/` (auto-download), or `LOCALAGENT_{CLASSIFIER,EMBEDDER}_ONNX` env override | exported by `ct-export-onnx` / `export_minilm_onnx.py`; sha256/size pinned in `DesktopAuxModels`. **Auto-downloads from the CDN on first run** (default `DesktopAuxModels.DEFAULT_BASE_URL`, PR #3); override the host with `-PauxModelBaseUrl=https://host/path` (baked into `desktop_build_info.properties`). Absent ⇒ classifier no-ops (pre-flight under-fires; explicit `web search …` still works). |
| Vosk acoustic model | app-data `models/vosk` or `LOCALAGENT_VOSK_MODEL` | optional; STT no-ops without it |
| Piper binary + voice (neural TTS) | `PiperBinaryStore` (`rhasspy/piper`, pinned `PiperRelease.TAG`) + `PiperVoiceStore` (HF `rhasspy/piper-voices`, `en_US-lessac-medium` ≈63 MB) → app-data `piper/` or `LOCALAGENT_PIPER_BINARY` | optional; only when the user picks the "piper" engine. Pinned sha256/size per OS/arch (Linux/macOS/Windows). Played in-JVM (Java Sound); falls back to OS shell-out if no prebuilt for the host (PR #66) |

App-data dir per OS:
- Linux: `~/.local/share/LocalAgent/`
- macOS: `~/Library/Application Support/LocalAgent/`
- Windows: `%LOCALAPPDATA%\LocalAgent\`

This keeps the installer small (runtime + app jars, no model) and lets the model be
updated independently of the app.

## GPU acceleration

| Backend | OS | User must provide | How to get it |
|---|---|---|---|
| Vulkan (LLM) | Linux/Windows | Vulkan-capable driver (Intel/AMD/NVIDIA) | **automatic** — the Vulkan `llama-server` variant downloads by default; CPU fallback if no driver |
| Metal (LLM) | macOS | nothing (OS-provided) | **automatic** — the macOS `llama-server` archive is Metal-capable; offload via `-ngl` |
| CUDA EP (classifier/embedder) | Linux/Windows + NVIDIA | CUDA runtime + cuDNN (CUDA 12.x) | `-PonnxGpu=true` build |

We never ship a vendor driver or the CUDA toolkit — only binaries that load against a
driver the user already has. The CPU path is always present as the fallback.

### LLM GPU offload — nothing to build

Because the LLM runs as a downloaded `llama-server` subprocess (not a bundled JNI
native), GPU offload is selected by **which prebuilt server archive is fetched**, and it
happens **automatically**:

- On **Linux/Windows** the **Vulkan** archive (cross-vendor — Intel/AMD/NVIDIA) is
  requested first; if no Vulkan driver is present the server fails to start and the
  engine falls back to the **CPU** archive. No operator build, no `libjllama`, no
  `-PllamaLibPath` (all retired with the JNI binding).
- On **macOS** a single Metal-capable archive serves both; offload is just `-ngl`.

Force the choice with `LOCALAGENT_LLAMA_SERVER_VARIANT` (`cpu` | `vulkan` | `auto`,
default `auto`), or point `LOCALAGENT_LLAMA_SERVER` at your own `llama-server` build
(e.g. a CUDA-enabled one) to bypass the download entirely. `activeAccelerator` reports
best-effort intent (a real offload probe is [deferred](#deferred-not-in-v010)).

> Bumping `LlamaServerRelease.TAG` to a newer llama.cpp release means refreshing every
> per-host asset's sha256/size from the release's `digest` field (CPU + Vulkan, all
> arches) in `LlamaServerRelease`.

## Headless / standalone deployment

The desktop agent is the **only** host that runs Jobs (cron + one-shot) and serves the
mobile↔desktop link, so it's useful to run it as an always-on background service — at
boot, without popping a window, no login required. Set the env var
**`LOCALAGENT_HEADLESS=1`** and the same launcher starts the full runtime (jobs, clock,
task queue, mobile-link server, warm LLM, ONNX classifier/embedder) **without opening the
main window**. What happens next depends on whether a system tray is available:

- **Tray available (a graphical login session)** → the agent starts **minimized to the
  system tray**. Click the tray icon's **Show** to open the chat UI on demand, or **Shut
  down** to quit. This is the recommended way to autostart on a desktop you log into.
- **No tray (a display-less server, or the GNOME/Wayland tray trap)** → the agent runs
  **fully windowless** and blocks until signalled. `SIGTERM` (systemd's default stop) /
  Ctrl-C trigger a clean shutdown (link server stop, model unload). This is the path a
  systemd/launchd service on a headless box takes.

Same gate convention as `DI_CHECK` / `LOCALAGENT_LLAMA_SERVER_VARIANT`. The windowless
fallback needs no display (X11/Wayland/Quartz) — safe on a true headless server.

> **One instance only.** A process-wide file lock (`<app-data>/.instance.lock`) is shared
> by both modes, so the headless service and the GUI can't both run (two warm models, two
> task/job consumers racing the same SQLite rows). Stop the service before opening the GUI,
> and vice versa.

### Implementation notes (CLAUDE.md invariant #53)

`LOCALAGENT_HEADLESS=1` only changes **window startup** — the full background runtime
(jobs / clock / tasks / link / warm-LLM / aux engines) is launched on `appScope`
**before** the `application{}` window block, so headless and GUI share the exact same
runtime bring-up. The tray probe runs first, then:

- **(a) tray available** → enter `application{}` with `windowVisible = false` (starts
  minimized to tray; the tray's Show / Shut-down drive it — the same machinery as
  hide-to-tray).
- **(b) no tray** (display-less server, or the GNOME/Wayland tray trap, invariant #46) →
  fully windowless: block the non-daemon main thread on a `CountDownLatch`, with a
  SIGTERM / Ctrl-C shutdown hook mirroring the GUI `shutdown()` (linkServer.stop /
  warmModel.unload / appScope.cancel — **no** `exitProcess`).

Nothing before the window touches AWT/Skia (`AppIcon` is `lazy`), so the windowless path
is display-less-safe. The single-instance file lock (`<app-data>/.instance.lock`) is
shared across modes (the "One instance only" note above). Same env-gate convention as
`DI_CHECK` / `LOCALAGENT_LLAMA_SERVER_VARIANT`.

### End-to-end deploy

1. **Build the standalone app on the target OS** (jpackage cross-builds nothing):

   ```bash
   cd android-app
   ./gradlew :desktopApp:packageDistributionForCurrentOS   # → .deb/.rpm | .dmg/.pkg | .msi/.exe
   # or, no OS packaging tools / no install:
   ./gradlew :desktopApp:createDistributable               # → build/compose/binaries/main/app/LocalAgent/
   ```

   The app-image / installer bundles the JDK runtime + app jars but **no models** — those
   download at first run (see [Models & assets](#models--assets--bundled-vs-downloaded)).

2. **Install it.** `.deb` → `/opt/local-agent/bin/LocalAgent`; `.dmg`/`.pkg` →
   `/Applications/LocalAgent.app`; Windows `.msi`/`.exe` → per-user
   `%LOCALAPPDATA%\LocalAgent\LocalAgent.exe`. A `createDistributable` app-image runs
   from its `bin/LocalAgent` in place.

3. **Provision models + keys once** (the service runs unattended, so seed first). Easiest
   is to launch the GUI once and let it download the GGUF + ONNX + (optional) Vosk into the
   app-data dir, fill the GGUF coordinates / Brave key, and — if you use the phone — pair
   it. Everything persists in the app-data dir
   ([per-OS paths](#models--assets--bundled-vs-downloaded)); the headless service inherits
   it. Alternatively point the `LOCALAGENT_*` model env vars at pre-staged files and pass
   the Brave key via the service environment. Verify headless before wiring startup:

   ```bash
   LOCALAGENT_HEADLESS=1 /opt/local-agent/bin/LocalAgent
   # graphical session → starts minimized to tray (banner: "background: ... minimized to tray");
   # display-less box  → runs windowless and blocks (banner: "headless (no tray): ...").
   ```

4. **Register it as a startup task** (templates in `android-app/desktopApp/packaging/`):

   **Linux — systemd user service** (`localagent.user.service`):
   ```bash
   cp android-app/desktopApp/packaging/localagent.user.service \
      ~/.config/systemd/user/localagent.service          # edit ExecStart to your install path
   systemctl --user daemon-reload
   systemctl --user enable --now localagent.service
   sudo loginctl enable-linger "$USER"                    # start at boot before login
   journalctl --user -u localagent -f                    # logs (the stderr banner)
   ```

   **Linux — systemd system service** (`localagent.system.service`): same, but edit
   `User=`/`Environment=HOME=` to the account owning the app-data dir, install to
   `/etc/systemd/system/`, and `sudo systemctl enable --now localagent`.

   **macOS — launchd** (`com.contextsolutions.localagent.plist`):
   ```bash
   cp android-app/desktopApp/packaging/com.contextsolutions.localagent.plist \
      ~/Library/LaunchAgents/
   launchctl load -w ~/Library/LaunchAgents/com.contextsolutions.localagent.plist
   ```

   **Windows — Task Scheduler** (no template file). Set the env var, then create a task:
   ```powershell
   setx LOCALAGENT_HEADLESS 1     # user-scoped; or set it inside the task's action
   schtasks /Create /SC ONLOGON /TN LocalAgent ^
     /TR "%LOCALAPPDATA%\LocalAgent\LocalAgent.exe"
   ```
   (Or drop a shortcut to `LocalAgent.exe` in `shell:startup`.) Env vars can also be set
   machine-wide with `setx /M`.

## Deferred (not in v0.1.0)

- **A real `activeAccelerator` offload probe.** GPU offload is automatic via the
  Vulkan/Metal `llama-server` variants (see [LLM GPU offload](#llm-gpu-offload--nothing-to-build)),
  but `activeAccelerator` still reports best-effort *intent* — a probe to *confirm*
  actual layer placement (rather than infer it) is deferred. A bundled CUDA
  `llama-server` variant is also not shipped (Vulkan covers NVIDIA on Linux/Windows);
  operators wanting CUDA point `LOCALAGENT_LLAMA_SERVER` at their own build.
- **Code signing / notarisation.** Now **wired** (env-gated macOS signing +
  notarization in `desktopApp/build.gradle.kts`; Windows `signtool` post-build) — see
  `docs/PRODUCTION_RUNBOOK.md`. The default local/CI build stays unsigned
  (Gatekeeper / SmartScreen warnings) until credentials are supplied; Linux `.deb`/`.rpm`
  remain unsigned (repo GPG signing is the follow-up).
- **ProGuard/R8-minified release bundle.** The CI builds the non-minified
  distribution; minifying the reflection/JNI-heavy deps (Koin, sqlite-jdbc, llama/
  ONNX/Vosk bindings) needs a keep-rules pass and is deferred behind the
  always-works guarantee.
