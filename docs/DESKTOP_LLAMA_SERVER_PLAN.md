# Desktop inference via `llama-server` (PR #55, Option 3)

Replaces the desktop JNI binding (`net.ladenthin:llama`) with llama.cpp's reference
**`llama-server`** subprocess, reached over localhost HTTP. This is the only path that
gives correct **multimodal (vision)** AND GPU acceleration on desktop, and it retires a
thin single-maintainer binding whose multimodal support is fundamentally broken.

## Why (root cause that forced this)

`net.ladenthin:llama` (all versions: 5.0.1 / 5.0.2 / main) **drops images before the
vision encoder**. Confirmed in its C++ (`src/main/cpp/jllama.cpp`):
`parse_oai_chat_params` extracts `image_url` into a local `files` vector and discards it;
every `tokenize_input_prompts(...)` call passes `nullptr` for the mtmd context. So the
`<__media__>` marker is never replaced by image embeddings — the model runs text-only and
hallucinates. The binding deleted the upstream server fork and didn't carry the mtmd
tokenization path over; there are no upstream issues/PRs fixing it. The bundled natives
are also CPU-only (hence the ~4 tok/s the user saw).

`llama-server` is the reference implementation: `/v1/chat/completions` with `image_url`
→ `mtmd` works and is maintained by the llama.cpp team, with prebuilt GPU (Vulkan/Metal/
CUDA/ROCm) server binaries per OS.

## Architecture

```
:shared commonMain  AgentLoop ── InferenceEngine (unchanged seam) ───────────────┐
:shared desktopMain  LlamaServerInferenceEngine (HTTP client, NEW) ──────────────┤
                       │ loadModel → ensure binary + start process + /health      │
                       │ generate  → POST /v1/chat/completions (SSE) → events      │
                     LlamaServerProcess (spawn/health/stop, NEW)                   │
                     LlamaServerBinaryStore (download/verify/extract, NEW)         │
                            ▼ spawns
                     llama-server  -m gemma.gguf  --mmproj mmproj.gguf  (ONE model load)
```

The agent core (`InferenceEngine`, `GenerationRequest`, `GenerationEvent`) is **unchanged**;
only the `desktopMain` implementation swaps from JNI to HTTP. **Android is untouched**
(it uses LiteRT-LM). The model is loaded **once** — in the server, not in the JVM — so no
double 5 GB residency.

## Components (new, `desktopMain`)

### 1. `LlamaServerBinaryStore` (`inference/`)
Resolves / downloads / verifies / extracts the prebuilt `llama-server` for the host.
- **Asset map:** pin a llama.cpp release tag (`b9478`) with a per-platform asset spec
  (name + sha256 + size), mirroring `DesktopModelSpec`. Archives are tiny (Linux CPU 13 MB,
  Vulkan 30 MB; macOS 9 MB; Windows CPU 15 MB) → **download on first run**, cache under
  `<app-data>/server/<tag>-<variant>/`. Reuses the `DesktopModelDownloader` streaming/
  resume/SHA pattern; only the unpack step is new.
- **Layout:** archive has one top dir `llama-<tag>/` with `llama-server` + all `.so`/
  `.dylib`/`.dll` incl. `libmtmd`. Extract flat into the cache dir; `chmod +x` the binary
  (Linux/macOS).
- **Extraction:** `.tar.gz` (Linux/macOS) via Apache Commons Compress + `GZIPInputStream`;
  `.zip` (Windows) via `java.util.zip`. (commons-compress is the one new desktop dep.)
- **Variants (done):** **CPU** + **Vulkan** (cross-vendor GPU — Intel/AMD/NVIDIA on
  Linux/Windows; macOS uses its Metal-capable archive, GPU = just `-ngl`). Each caches
  under its own dir (keyed by the asset label, e.g. `ubuntu-vulkan-x64`) so they coexist.
  The engine requests the GPU variant by default (`InferenceConfig.accelerator` AUTO/GPU)
  and **falls back to the CPU archive** if the GPU server can't start. Note: the Vulkan
  build also self-degrades to CPU when no Vulkan device is present (verified), so the
  fallback is a backstop for a hard crash. CUDA/ROCm (needs the extra cudart redist) is a
  later opt-in.
- **Env overrides:** `LOCALAGENT_LLAMA_SERVER` → absolute path to an existing
  `llama-server` (skips download); `LOCALAGENT_LLAMA_SERVER_VARIANT` = `cpu`|`vulkan`|`auto`
  forces the variant. Mirror `LOCALAGENT_MMPROJ_GGUF`.

### 2. `LlamaServerProcess` (`inference/`)
Owns one server process for the app's lifetime.
- Picks a free `127.0.0.1` port; generates a random `--api-key` (sent on every request) so
  other local processes can't drive our server.
- Spawns: `llama-server -m <gguf> [--mmproj <mmproj>] --host 127.0.0.1 --port <p>
  -c <ctx> -ngl <n> --jinja --no-webui --api-key <key>` with `LD_LIBRARY_PATH` /
  `DYLD_LIBRARY_PATH` = the binary's dir (libs sit beside it).
- Streams the process stdout/stderr into the existing `[LlamaServer]` logger, so the user
  keeps seeing the native `srv`/`slot` lines they've been reading.
- Polls `GET /health` until `{"status":"ok"}` (timeout ~120 s for cold model load), returns
  the base URL. `close()` → `destroy()` then `destroyForcibly()`; also a JVM shutdown hook.

### 3. `LlamaServerInferenceEngine` (`inference/`, implements `InferenceEngine`)
- `loadModel(modelPath, config)`: ensure binary present → start `LlamaServerProcess`
  (passing `--mmproj` when `config.enableVision` and the projector resolves, `-ngl` from
  accelerator, `-c` from `kvCacheTokens`) → await `/health` → return a `ModelHandle`
  wrapping the process + base URL + a Ktor `HttpClient` (CIO).
- `generate(handle, request, _)`: build the OAI chat body from `request` (system
  instruction + history; trailing USER turn becomes multipart `content` with the
  `image_url` data-URI when `imageBytes != null`, **image first** to match Android), POST
  `/v1/chat/completions` with `stream:true`, parse the SSE `data:` deltas → emit
  `GenerationEvent.TokenChunk`; on `[DONE]`/finish emit `Done`; errors → `Error`. Map
  `SamplingParams`→`temperature`/`top_k`/`top_p`, `maxTokens`→`max_tokens`,
  `stopSequences`→`stop`. Cancelling the collector cancels the HTTP call (closes the
  connection → server frees the slot). `ThinkingStripper` retained for reasoning GGUFs.
- Tool calls: desktop never used the LLM tool channel (search is injected by
  `PreflightRouter`), so `toolDispatcher` stays ignored — same as the JNI engine.
- **Drop the vision-only minimal system prompt.** It was a workaround for the model never
  seeing the image; with real mtmd the normal agent system instruction works (Android
  proves it), so vision and text turns share one prompt path.

## Wiring changes

- `DesktopModule`: bind `InferenceEngine` → `LlamaServerInferenceEngine(binaryStore, …)`;
  add `single { LlamaServerBinaryStore(...) }`. Keep `DesktopModelInventory` (GGUF) + the
  named mmproj inventory/downloader.
- `WarmModel`: unchanged in shape — `ensureLoaded()` now starts the server; `unload()` stops
  it. `enableVision=true` stays (controls `--mmproj`). `isModelPresent()` still gates on the
  GGUF; the server binary is ensured inside `loadModel`.
- `Main.kt`: add `serverBinary.ensurePresent()` alongside the model + mmproj downloads; keep
  the eager warm (now = start server) gated on GGUF (+mmproj) present. `warmModel.unload()`
  on Quit already covers process teardown; add a shutdown hook as a backstop.
- `desktopApp/build.gradle.kts`: remove the `net.ladenthin.llama.lib.path` jvmArgs block.

## Retire the JNI binding

- Delete `LlamaCppInferenceEngine.kt`; remove `llama-jni` from the version catalog + the
  Sonatype **snapshots repo** in `settings.gradle.kts` + the desktop dep.
- Replace `DesktopVisionOaiTest` with a test for the HTTP request-body builder
  (`buildChatRequestJson`) — same image-first/multipart assertions, now against the JSON
  POSTed to the server.
- Update `:desktopHarness` (`EngineSmoke`/`Main`) to construct `LlamaServerInferenceEngine`
  (or mark the JNI smoke obsolete). Keep `ThinkingStripper` (reused by the new engine).

## Increments (commits within PR #55) — all ✅ shipped

1. ✅ **Acquisition** — `LlamaServerBinaryStore` + asset map (CPU + Vulkan) + extract (system
   `tar`, preserves `.so` symlinks) + a `desktopTest` for variant resolution.
2. ✅ **Lifecycle** — `LlamaServerProcess` (spawn/health/stop, log tee, api-key, free port).
3. ✅ **HTTP engine** — `LlamaServerInferenceEngine` + `buildChatRequest` + SSE `parseStreamChunk`
   + request-body unit test.
4. ✅ **Wire + retire** — DI/WarmModel/Main; deleted JNI engine + dep + Sonatype snapshot repo
   + `-PllamaLibPath`; harness updated.
5. ✅ **Docs** — `DESKTOP_PORT_PLAN.md` + `CLAUDE.md` + this file.

**Extras shipped in the same PR (beyond the original plan):**
- ✅ **GPU (Vulkan) variant** with CPU fallback (`assetForHost(wantGpu)`); `InferenceConfig.accelerator`
  + `LOCALAGENT_LLAMA_SERVER_VARIANT` select it. Metal is automatic in the macOS archive.
- ✅ **First-launch GPU race fix** — a `Mutex` in `ensure()` serializes the prefetch + warm so they
  don't race the same download (which previously tripped a spurious CPU fallback on run #1).
- ✅ **Single-user perf defaults** — `--parallel 1` + `-fa on`; `LOCALAGENT_LLAMA_SERVER_ARGS`
  passthrough for rebuild-free tuning (KV quant, etc.). NOT `--swa-full` (slows the decode bottleneck).
- ✅ **Default model → Gemma 4 E2B** (`unsloth/...E2B...Q4_K_M`, ~3.1 GB) — ~2× decode vs E4B.
- ✅ **Download banner** — `SessionState.Downloading(fraction)` shows "Downloading model files… N%"
  aggregated across GGUF + mmproj + server binary (`DesktopChatSessionController.bindDownloads`).
- ✅ **Eager-warm after first-run download** — the warm now *awaits* model+mmproj+server present
  (not a one-shot startup check), so a fresh-install desktop warms once downloaded; Android stays
  lazy (invariant #22).

## Verification

- Build gates (per increment): `:shared:compileKotlinDesktop`, `:desktopApp:compileKotlin`,
  `:shared:desktopTest`, `DI_CHECK=1 :desktopApp:run`, `:desktopHarness:compileKotlin`.
- **Operator on-device (the real test):** text chat streams; **image turn translates the
  German photo correctly** (the whole point); server starts/stops with the app; logs show
  `srv … loaded multimodal model` + real image token counts (~256), not a `<__media>` leak.

## Risks / notes

- **Process lifecycle:** orphaned server if the JVM is killed -9 — mitigated by the
  shutdown hook + binding to an ephemeral localhost port (next launch picks a fresh port);
  consider a PID/own-the-port check later.
- **Startup latency:** server cold start + model load (a few seconds) — covered by the
  eager warm; `/health` gates first use.
- **Cross-platform:** archives differ (`.tar.gz` vs `.zip`, `.exe`, lib-path env var);
  abstract per-OS in `LlamaServerBinaryStore`. macOS Gatekeeper may quarantine a downloaded
  binary — may need `xattr -d com.apple.quarantine` (note for the macOS pass).
- **GPU:** CPU + Vulkan done (Vulkan default with CPU fallback); Metal is automatic in the
  macOS archive. CUDA/ROCm (extra runtime redist) is a later opt-in. On the user's Intel Arc
  iGPU the Vulkan build should turn ~4 tok/s into tens.
- **Security:** bind `127.0.0.1` only + random `--api-key`; never expose the port.
- **Perf tuning (`LlamaServerProcess`):** single-user defaults `--parallel 1` (auto picks 4,
  splitting the KV cache for no benefit + 4× memory) and `-fa on` (flash attention — helps
  Gemma's SWA decode). `LOCALAGENT_LLAMA_SERVER_ARGS` appends extra flags without a rebuild
  (e.g. KV-cache quant `--cache-type-k q8_0 --cache-type-v q8_0` for a bandwidth-bound iGPU).
  Decode throughput is ultimately GPU-bound (~19 tok/s for 4B-Q4 on a mobile Arc iGPU, ~5×
  the CPU path); the bigger levers are answer length and model size (E2B / lower quant), not
  flags. **Do NOT default `--swa-full`** — it makes SWA layers attend the full context,
  slowing the decode that is the bottleneck (it only saves cheap prompt re-eval).
- **Bundling vs download:** download-on-first-run (chosen) keeps the installer small and
  reuses the model-download UX; an offline/bundled option can come later.
