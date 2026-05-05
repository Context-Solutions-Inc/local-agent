# M0 Inference Spike — Runbook

**Audience:** the engineer running the WS-1 benchmark on a Pixel 7
**Goal:** capture clean, reproducible measurements of Gemma 4 (E2B) inference on
Pixel 7 + Android 16. E4B was tested first and ruled out by LMKD-induced thrash;
see `M0_DECISION_MEMO.md` Decision 1. Output feeds the rest of that memo.

---

## Prerequisites

- Pixel 7 (non-Pro, non-a) running Android 16 stable or current beta
- USB cable + adb installed on dev machine
- JDK 17, Android SDK with platform 36, build-tools matching AGP 8.7.x
- Brave dev key not required for this spike (no network involved)
- Access to LiteRT-LM Android AAR (Maven coordinates or local AAR file)
- Gemma 4 E2B model artifact (`.litertlm`) — ~2.58 GB; see Stage 3 for download

---

## Stage 1 — Validate the harness with the stub engine

The point: confirm the spike harness, metrics collection, thermal monitor, and
result persistence all work end-to-end before swapping in the real engine. If
something is broken in the harness it's much easier to debug against a stub.

```bash
cd android-app
./gradlew :androidApp:installDebug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity
```

In the SpikeActivity UI, tap **Run benchmark**. The stub will:

- Simulate a 6 s cold load
- Emit canned tokens at ~8 tok/s
- Run all 5 canonical prompts
- Write `filesDir/spike-results/spike-<runId>.json`

Pull the result:

```bash
adb shell run-as com.contextsolutions.mobileagent.debug ls files/spike-results/
adb shell run-as com.contextsolutions.mobileagent.debug cat files/spike-results/spike-<runId>.json > stub-run.json
```

Confirm:

- All 5 prompts ran
- `firstTokenLatencyMs` ≈ 4,000 (matches stub config)
- `sustainedTokensPerSecond` ≈ 8.0
- `peakRssBytes` is non-zero
- `thermalStateMaxObserved` ≥ 0

If any of these is missing or implausible, fix the harness before moving on.

---

## Stage 2 — Wire in real LiteRT-LM ✅ DONE

1. ✅ LiteRT-LM dep pinned (`com.google.ai.edge.litertlm:litertlm-android:0.10.2`)
   in `gradle/libs.versions.toml` and applied to `:shared`'s `androidMain`.
2. ✅ `LiteRtInferenceEngine` lives at
   `android-app/shared/src/androidMain/kotlin/com/contextsolutions/mobileagent/inference/`
   and implements the `InferenceEngine` contract:
   - `loadModel`: opens the model with `Engine(EngineConfig)` and the requested
     accelerator (`Backend.CPU` / `GPU` / `NPU`), respects `kvCacheTokens` via
     `EngineConfig.maxNumTokens`, uses the app's cache dir for GPU kernel reuse.
   - `generate`: per-call `engine.createConversation(...).sendMessageAsync(prompt)`
     bridged to the agent loop's `Flow<GenerationEvent>` as `TokenChunk`s.
   - `unload`: closes the Engine.
3. ✅ `InferenceModule` (in `androidApp/.../di/`) chooses between
   `LiteRtInferenceEngine` and `StubInferenceEngine` via
   `BuildConfig.USE_STUB_ENGINE`. Default is the real engine; flip with
   `./gradlew :androidApp:assembleDebug -PuseStubEngine=true` for fast UI
   iteration without waiting on cold model load.

Phase 1 deferrals (tracked in `LiteRtInferenceEngine` kdoc):
- Function-call parsing (`ConversationConfig.tools` + `automaticToolCalling`) is
  not yet wired — M3 hooks the pre-flight + tool-calling code into this layer.
- `GenerationRequest.stopSequences` ignored — no stop-sequence surface in 0.10.2.
- `seed` is randomized per call; M1 plumbs an optional caller-supplied seed
  through `GenerationRequest` for reproducibility in eval.

---

## Stage 3 — Get the model on device ✅ READY

Model: [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
(2.58 GB `.litertlm` bundle).

> 📋 **M0 finding (Decision 1):** the larger E4B variant (3.65 GB) is **not
> viable on Pixel 7**. We tried it and the Linux Low Memory Killer killed our
> process during `engine.initialize()` along with ~30 other apps as
> system-wide thrashing hit 463%. Logged in `M0_DECISION_MEMO.md` Decision 1.
> E2B is the validated baseline for the spike from this point forward.

Download from HuggingFace (one-time):

```bash
# Option A: huggingface-cli (now called hf)
pip install -U huggingface_hub
hf download litert-community/gemma-4-E2B-it-litert-lm \
    gemma-4-E2B-it.litertlm --local-dir ~/models

# Option B: direct download via web browser
# https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm
```

Push to the app's external files dir on the connected Pixel 7:

```bash
adb shell mkdir -p /sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/
adb push ~/models/gemma-4-E2B-it.litertlm \
    /sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/
```

The spike UI looks for the file at exactly that path (it derives it from
`context.getExternalFilesDir("models")` and looks for `gemma-4-E2B-it.litertlm`).
The "Run benchmark" button is disabled until the file is present, and the UI
displays its size when found.

(M1 replaces all of this with the WorkManager-driven download flow per PRD §3.5.)

---

## Stage 4 — Run the real benchmark

Run THREE times back-to-back to capture cold load, warm load, and thermal
trajectory:

1. Cold: launch app, immediately tap Run. Captures cold model load time.
2. Warm: tap Run again 30 s later. Measures sustained perf without cold-load tax.
3. Sustained: leave app open, tap Run a third time after 60 s. By this run the
   device has been generating for 5+ minutes total — captures thermal envelope.

Conditions to control:

- **Plugged in vs battery:** run BOTH. Battery results are what users actually experience.
- **Screen on:** always on for these runs (screen-off would pause the activity).
- **Ambient:** record room temp. Hot environment will throttle thermals faster.
- **Other apps:** force-close everything else first (`adb shell am kill-all`).

Pull each run JSON. Three files per condition.

---

## Stage 5 — Try each accelerator path

Re-run the benchmark with `InferenceConfig.accelerator` set to each of `NPU`, `GPU`,
`CPU` (assuming the LiteRT-LM AAR exposes these). For each, capture:

- Whether the runtime accepts the accelerator request (logs)
- First-token p50, sustained tok/s
- Peak RSS, peak native heap
- Thermal trajectory

The accelerator decision in `M0_DECISION_MEMO.md` is driven by these results.

---

## Stage 6 — (Optional) Re-attempt E4B with mitigations

E4B was killed by LMKD on first attempt (Decision 1 in the memo). Before fully
abandoning it, the following are worth trying as Phase 1.x experiments — none
are part of the Phase 1 critical path:

- Reduce `InferenceConfig.kvCacheTokens` from 8192 → 2048 (matches the model's
  benchmark default; cuts KV-cache RAM by ~75%).
- Force `Accelerator.CPU`. GPU backends sometimes duplicate weight buffers in
  GPU memory; CPU-only avoids this and may bring peak RSS just under the
  per-app ceiling.
- Push the model to internal storage (`/data/data/.../files/models/`) instead
  of `/sdcard/...` — slightly different mmap behavior on some devices.
- Disable the spike's other auxiliary services (foreground service, etc.)
  while testing.

If any combination lets E4B run cleanly to the end of `sustained_5min`, log the
combo in the memo and mark Decision 1 as "E4B with [config]". Otherwise the
finding stands: Pixel 7 ships E2B in Phase 1.

---

## Pulling all results off-device

```bash
mkdir -p ~/spike-results
adb shell run-as com.contextsolutions.mobileagent.debug \
    ls files/spike-results/ | tr -d '\r' | while read f; do
        adb shell run-as com.contextsolutions.mobileagent.debug \
            cat "files/spike-results/$f" > ~/spike-results/$f
    done
```

---

## Filling in the memo

Open `docs/M0_DECISION_MEMO.md` and copy the relevant numbers from the result
JSONs into each table. Make and document each of the five open decisions.

Sign off the memo and circulate. M1 doesn't start until M0 is signed.
