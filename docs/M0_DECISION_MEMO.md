# M0 Decision Memo — Pixel 7 Inference Envelope

**Status:** DRAFT — populated 2026-05-05 from spike run `ca42ff6a-6911-4ad3-84d1-29f264ad8ed2`. 4 of 5 decisions ratified; Decision 3 (foreground service) deferred to M1.
**Owner:** TBD
**Companion:** `docs/SPIKE_RUNBOOK.md`, `PHASE1_PLAN.md` §2.3, §6, §7

---

## Purpose

The PRD and PHASE1_PLAN both assume Gemma 4 E4B Q4 on a Tensor G2 / 8 GB Pixel 7 will
meet the (relaxed) Phase 1 perf envelope. This memo records the actual measurements
from the M0 spike and ratifies — or revises — that assumption before we lock the
architecture for M1.

Three decisions hinge on the data captured here:

1. **Model choice:** Gemma 4 E4B (PRD default) vs E2B fallback?
2. **Accelerator path:** NPU (Tensor G2 EdgeTPU) / GPU (Mali-G710) / CPU?
3. **Foreground service contract:** does the `specialUse` foregroundServiceType
   survive Android 16's stricter rules, and does the OS keep us alive long enough
   for a 1024-token generation?

---

## 1. Spike configuration

| | Value |
|---|---|
| Device | Google Pixel 7 |
| Android version | 16 (SDK 36) |
| LiteRT-LM version | 0.10.2 (`com.google.ai.edge.litertlm:litertlm-android`) |
| GPU delegate | Play Services TFLite GPU 16.4.0 (resolves OpenCL) |
| Model artifact | `litert-community/gemma-4-E2B-it-litert-lm` (gemma-4-E2B-it.litertlm, 2.58 GB) |
| KV cache tokens | 8,192 (PRD default) |
| Accelerator | AUTO → `Backend.GPU()` (Mali-G710) |
| Canonical prompt set | `CanonicalPrompts.ALL` (5 prompts, see SpikeRunner.kt) |
| Run JSON | `~/spike-results/spike-ca42ff6a-6911-4ad3-84d1-29f264ad8ed2.json` |
| Run started | 2026-05-05 (epoch ms 1777980756179) |
| Run duration | 196 s end-to-end (5 prompts) |
| Power / screen | (engineer to confirm — not captured by harness) |
| Ambient | (engineer to confirm — not captured by harness) |

---

## 2. Measurements

From the spike run JSON. Bottom line: **measured numbers beat the relaxed Phase 1
targets across the board, and approach the original PRD's Pixel 9 Pro targets.**

### 2.1 First-token latency

| Prompt | Cold load? | First token (ms) |
|---|---|---|
| short_factual | yes (4343 ms) | 928 |
| medium_explanation | no | 540 |
| long_creative | no | 400 |
| long_technical | no | 563 |
| sustained_5min | no | 546 |

| Aggregate | PRD (Pixel 9 Pro) | Phase 1 (Pixel 7) | **Measured** |
|---|---|---|---|
| First token p50 | <2,000 ms | <4,000 ms | **546 ms** ✅✅ |
| First token p95 | <2,500 ms | <4,000 ms | **928 ms** ✅✅ |

### 2.2 Sustained throughput

| Prompt | Tokens | Duration (ms) | tok/s |
|---|---|---|---|
| short_factual | 8 | 1,490 | 12.5 |
| medium_explanation | 150 | 9,724 | 16.2 |
| long_creative | 378 | 26,722 | 14.3 |
| long_technical | 564 | 44,338 | 12.9 |
| sustained_5min | 1,255 | 108,974 | 11.6 |

| Aggregate | PRD (Pixel 9 Pro) | Phase 1 (Pixel 7) | **Measured** |
|---|---|---|---|
| Sustained mean | ≥15 tok/s | ≥8 tok/s | **13.5 tok/s** ✅ |
| Sustained min (longest prompt) | — | — | **11.6 tok/s** ✅ |

Throughput drifts down as KV cache fills (12.5 → 16.2 → 14.3 → 12.9 → 11.6).
Drift is mild and within budget. The 16.2 outlier on `medium_explanation` is
likely overhead noise at small generation sizes (9.7 s total).

### 2.3 Memory

| | Target | **Measured** |
|---|---|---|
| Cold-load peak PSS | < 4 GB (PRD §4.2) | **3.11 GB** ✅ |
| Sustained-generation peak PSS | < 4 GB | **3.52 GB** ✅ |
| Native heap peak | (informational) | 565 MB |

PSS climbs gradually across prompts (3.11 → 3.19 → 3.35 → 3.44 → 3.52 GB)
as the KV cache fills. Plenty of headroom under the 4 GB ceiling.

### 2.4 Thermal

| | Value (state code) |
|---|---|
| Thermal status at start of run | LIGHT (1) |
| Thermal status at end of `sustained_5min` | MODERATE (2) |
| Peak observed across run | MODERATE (2) |

`THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6`

State 2 (MODERATE) was first observed at the end of `long_technical` (44 s of
generation) and held through `sustained_5min` (109 s of generation). Never
reached SEVERE (3) or CRITICAL (4); throttling kicked in via Pixel's CDEV
manager (cdev_thermal-gpufreq-0 climbed 2 → 3 → 4 in vendor logs) but the
high-level Android thermal API stayed at MODERATE.

### 2.5 Cold load

| | Target | **Measured** |
|---|---|---|
| Time to load model (cold) | 2–4 s (PRD) / 4–8 s (Phase 1 Pixel 7) | **4.3 s** ✅ |

At the boundary of the PRD's original target, comfortably inside the Phase 1
relaxed target.

---

## 3. Open decisions

### Decision 1 — Model choice ✅ DECIDED 2026-05-04

- [ ] **Stay on Gemma 4 E4B.** Numbers meet the relaxed Phase 1 envelope.
- [x] **Switch to Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`).**
      E4B exceeds memory budget on Pixel 7.

Evidence:
- E4B artifact: `litert-community/gemma-4-E4B-it-litert-lm`, 3.65 GB on disk.
- HuggingFace model card reports ~3.28 GB CPU RSS on Galaxy S26 Ultra.
- On Pixel 7 (8 GB RAM, ~4 GB per-app ceiling per PHASE1_PLAN §2.2), `engine.initialize()`
  triggered the Linux Low Memory Killer ~4.4 s into cold load. lmkd killed
  ~30 other processes (launcher, gms, search, keyboard, Chrome sandbox renderer,
  Disney World app) trying to free memory; system thrash hit 463%; our
  process was killed before initialize() could complete.
- Switched to E2B (2.58 GB). Per the runbook, this is the validated baseline.
- See Stage 6 of `SPIKE_RUNBOOK.md` for the Phase-1.x mitigations to revisit
  E4B (smaller KV cache, CPU-only backend) if perf becomes a problem.

### Decision 2 — Accelerator path ✅ DECIDED 2026-05-05

- [ ] **NPU (Tensor G2 EdgeTPU).** Tried; LiteRT-LM auto-registration logs
      `kLiteRtStatusErrorInvalidArgument`. EdgeTPU not surfaced to apps.
- [x] **GPU (Mali-G710 via OpenCL).** Works once `play-services-tflite-gpu`
      16.4.0 is on the classpath. Sustained 13.5 tok/s mean, 11.6 tok/s on
      the longest prompt. This is the validated path.
- [ ] **CPU only.** Available as fallback; not benchmarked since GPU works.

Evidence: spike run `ca42ff6a-6911-4ad3-84d1-29f264ad8ed2`, all 5 prompts
generated successfully on `Backend.GPU()` after adding the Play Services
TFLite deps (commit `22b351e`). `LiteRtInferenceEngine.resolveBackend` keeps
`Accelerator.AUTO → Backend.GPU()`.

Revisit when: LiteRT-LM ships a Vulkan delegate (would let us drop the
Play Services dependency), or when Tensor G2 NPU support lands upstream.

### Decision 3 — Foreground service contract ✅ DECIDED 2026-05-05

- [x] **`specialUse` foregroundServiceType for inference is sufficient on
      Android 16.** Validated end-to-end on Pixel 7 via M1 WS-1 Phase C's
      minimal chat surface (PHASE1_PLAN §5 M1 exit-gate Drill 9).

Evidence: `InferenceSessionManager.markActiveGeneration` calls
`ContextCompat.startForegroundService` on `InferenceForegroundService`
(`foregroundServiceType="specialUse"` per `AndroidManifest.xml`). With a
generation in flight, pressing the Home button does not kill the process — the
FGS keeps it alive until the Flow completes. Coming back to the app shows the
fully-streamed response.

Caveats surfaced during validation:
1. **WorkManager's `SystemForegroundService` needs an explicit
   `foregroundServiceType` merge** (`tools:node="merge"` in our manifest) for
   any worker that calls `setForeground(...)`. We added `dataSync` for the
   model download. Without this, the worker process throws
   `IllegalArgumentException: foregroundServiceType 0x1 is not a subset of 0x0`
   at the moment WorkManager tries to promote it. (Now in CLAUDE.md hard
   invariants.)
2. **`POST_NOTIFICATIONS` runtime permission is required on Android 13+** for
   the FGS notification to be visible. The service runs regardless of grant —
   so Decision 3 (process survival) holds either way — but users won't see the
   notification without it. `MainActivity.ensureNotificationPermission`
   requests it on first launch.

Revisit if: a future foreground worker needs a different service type
(append `|specialUse` etc. to the manifest merge), or if Android tightens
`specialUse` rules in future SDKs (currently ≥36).

### Decision 4 — KV cache size ✅ DECIDED 2026-05-05

- [x] **8,192 tokens (PRD default) is feasible.**
- [ ] **Reduce.** Not needed.

Evidence: 8K KV cache + E2B + GPU peak PSS 3.52 GB during `sustained_5min`
(1,255 tokens generated). Comfortably under the 4 GB per-app ceiling. The
KV cache scales with active context, not with the configured cap, so the
8K cap is essentially "ceiling for worst case" and our worst case fits.

### Decision 6 — Classifier architecture ✅ DECIDED 2026-05-09 (M3 Phase H)

- [x] **Single shared encoder + 3 task heads** (preflight 3-class, memory_presence 2-class, memory_category 6-way multi-label).
- [ ] Two-separate-classifiers fallback. Tried in M3 Phase F iter 2; preflight-only training was *worse* (precision 0.863 vs 0.886 multi-task, recall 0.809 vs 0.868). Multi-task acts as encoder regularization.
- [ ] Three-separate-models. Not attempted — combined cost would exceed §4.2's 50 MB per-classifier budget by 3×.

Evidence: M3_PLAN.md Phase F iteration log. The shared encoder ships as
`preflight_memory_shared_v1.0.0_int8.tflite` (67.7 MB), satisfying the
combined classifier+embedder 200 MB §4.2 footprint with ~107 MB headroom.

### Decision 7 — Classifier base model ✅ DECIDED 2026-05-09 (M3 Phase H)

- [x] **DistilBERT-base-uncased** (66.4 M params, 67.7 MB INT8 .tflite).
- [ ] MobileBERT. Not attempted — DistilBERT-base hit the perf ceiling on
      the v1.0 dataset before the architecture-change branch fired in
      M3_PLAN's day-5 decision tree (we were within 3-7pp on every §7
      metric, not far enough to justify a base swap).
- [ ] DeBERTa-v3-base (130 MB INT8). Listed as v1.x stretch in the model
      card — would likely close the 6.4pp precision gap but exceeds the
      single-classifier 50 MB budget.

### Decision 8 — Memory v1 scope ✅ DECIDED 2026-05-09 (M3 Phase H)

- [x] **Memory v1 = presence (binary) + category (6-way multi-label)**.
      Span-text generation is deferred to v1.x.
- [ ] Memory v1 = full extraction with span markers (PRD §3.2.4 v1 path).
      Span tagging requires sequence-level outputs which complicate the
      .tflite signature and the M4 / WS-9, WS-10 integration. Per PRD
      §3.2.4 ("v1.x, Gemma 4 itself can be used to generate well-formed
      memory text via a brief background inference call"), we can route
      span generation through Gemma at extraction time without retraining.

Evidence: M3_PLAN.md §2 ratified decisions; classifier outputs presence
+ category only, span text generation runs through Gemma in the agent
layer (M5 work).

### Decision 5 — Idle unload aggressiveness ✅ DECIDED 2026-05-05

- [x] **5-minute idle unload (PRD default) is sufficient.**
- [ ] **Need more aggressive or onTrimMemory()-driven unload.** Not yet.

Evidence: thermal hit MODERATE (2) max under 109 s of sustained generation
and never SEVERE (3). Under typical conversational usage (bursts of
generation separated by minutes of idle), the device should stay in LIGHT/
MODERATE indefinitely. The 5-minute idle unload reclaims ~3.5 GB when the
user steps away — that's the mechanism that keeps us survivable across
multi-app workflows on an 8 GB device.

Still recommend wiring `onTrimMemory()` (PHASE1_PLAN §6) as belt-and-suspenders
for cases where another foreground app surges memory while the model is loaded.
That's M1 polish, not a Decision-5 reopener.

---

## 4. 16 KB native page alignment check ✅ VERIFIED 2026-05-05

Android 15+ requires 16 KB-aligned `.so` files. Verified directly against the
arm64-v8a `.so` shipped in the LiteRT-LM 0.10.2 AAR (no device required —
inspected the artifact in the Gradle cache, which is the same binary that gets
packaged into our APK):

```
$ objdump -p ~/.gradle/caches/9.3.1/transforms/.../liblitertlm_jni.so | grep LOAD
    LOAD off    0x0000000000000000 vaddr 0x0000000000000000 paddr 0x0000000000000000 align 2**14
    LOAD off    0x000000000130c000 vaddr 0x000000000130c000 paddr 0x000000000130c000 align 2**14
    LOAD off    0x00000000013b8500 vaddr 0x00000000013bc500 paddr 0x00000000013bc500 align 2**14
```

`align 2**14` = 16384 bytes = 16 KB. All three `LOAD` segments are 16 KB-aligned.
The AAR ships exactly one .so per ABI (`jni/arm64-v8a/liblitertlm_jni.so`,
`jni/x86_64/liblitertlm_jni.so`); only the arm64-v8a one ends up on Pixel 7. No
upstream issue needed; no 4 KB-emulated build needed.

Re-run after a LiteRT-LM version bump (catalog `litertlm` version) to confirm
alignment is preserved.

---

## 5. Risks surfaced

New risks not previously in PHASE1_PLAN §7:

1. **Play Services TFLite is a runtime dependency for GPU.** Without
   `play-services-tflite-gpu` (delivered via Google Play Services), our app
   can't use `Backend.GPU()`. Implications: devices without recent Play
   Services (CN market, AOSP forks, GrapheneOS) will fall back to CPU, which
   we have not benchmarked. Mitigation: explicitly degrade to `Accelerator.CPU`
   when GPU init fails; surface a warning in the UI; add this to the
   compatibility matrix in M5/M6.
2. **`GenerationRequest.maxTokens` is a no-op.** LiteRT-LM 0.10.2's public
   API doesn't expose a per-call max-tokens cap; the model generates until
   end-of-turn. Already documented in `LiteRtInferenceEngine` kdoc, but
   surfaces here because the spike's `sustained_5min` produced 1,255 tokens
   despite a configured cap of 512. Implications for the agent loop: tool-call
   detection and stop-sequence enforcement (PRD §3.2.2) will need to cancel
   the Flow at the parser layer, not rely on the engine to stop.
3. **Pixel CDEV throttling can drop GPU clocks more than the high-level
   thermal API reflects.** During the spike we saw the vendor cdev cooling
   state climb 2 → 3 → 4 while `PowerManager.currentThermalStatus` stayed at
   MODERATE (2). If we want fine-grained throttling signal in production,
   we'll need either a vendor sysfs tap (privileged) or to infer it from
   measured tok/s drift.

---

## 6. Next steps

- [x] Update `PHASE1_PLAN.md §2.3` with measured perf targets — DONE in same commit.
- [x] 16 KB native page alignment check — DONE 2026-05-05 (Section 4 above).
- [x] Add explicit `Accelerator.CPU` fallback in `LiteRtInferenceEngine` when
      `Backend.GPU()` init throws (Risk 1) — DONE in M1 WS-1 Phase A
      (`LiteRtInferenceEngine.tryInitialize`). Engine returns the actual
      accelerator on `ModelHandle.activeAccelerator` so degraded mode is visible
      to UI/telemetry.
- [x] Wire `onTrimMemory()` to unload Gemma early under system pressure
      (PHASE1_PLAN §6) — DONE in M1 WS-1 Phase A
      (`LocalAgentApplication.onTrimMemory` → `InferenceSessionManager.forceUnload`).
- [x] Decision 3 (foreground service contract) — VALIDATED 2026-05-05 on
      Pixel 7 via M1 WS-1 Phase C exit-gate Drill 9. `specialUse`
      `foregroundServiceType` keeps the process alive across Home-button
      backgrounding for the duration of a generation. Two on-device caveats
      recorded (manifest merge for WorkManager's FGS, runtime
      POST_NOTIFICATIONS) — see Decision 3 above.
- [ ] Engineer to confirm power state + ambient temp for the run on record
      (Section 1) so the numbers can be reproduced.
- [ ] Sign off this memo. Distribute to the team.
