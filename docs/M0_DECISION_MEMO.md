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

### Decision 3 — Foreground service contract ⏳ DEFERRED TO M1

The spike runs entirely in a foreground Activity; Android keeps it alive while
visible. The `specialUse` foregroundServiceType in `AndroidManifest.xml` is
declared but never exercised by the spike — it kicks in once chat lives
behind a backgroundable surface (M1's WS-3 + WS-11).

Decision deferred to M1 because:
- Useful test would be: start generation → background the app → confirm the
  service stays alive long enough for a 1024-token completion.
- Requires the chat UI (M1) to exist, since the spike's SpikeActivity itself
  isn't representative of the production foreground-service flow.

Action: re-open this decision when M1's chat path is wired and add an
explicit test in the M1 milestone exit criteria.

### Decision 4 — KV cache size ✅ DECIDED 2026-05-05

- [x] **8,192 tokens (PRD default) is feasible.**
- [ ] **Reduce.** Not needed.

Evidence: 8K KV cache + E2B + GPU peak PSS 3.52 GB during `sustained_5min`
(1,255 tokens generated). Comfortably under the 4 GB per-app ceiling. The
KV cache scales with active context, not with the configured cap, so the
8K cap is essentially "ceiling for worst case" and our worst case fits.

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

## 4. 16 KB native page alignment check

Android 15+ requires 16 KB-aligned `.so` files. Run:

```bash
adb shell objdump -p /data/app/.../lib/arm64/libliteRtLm.so | grep LOAD
```

Expected: alignment of `0x4000` (16 KB) on each `LOAD` segment.

Result: _(fill in)_

If not aligned, file upstream issue with LiteRT-LM team and use 4 KB-emulated build temporarily.

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
- [ ] Decision 3 (foreground service contract) — re-open when M1's chat path is wired.
- [ ] Add explicit `Accelerator.CPU` fallback in `LiteRtInferenceEngine` when
      `Backend.GPU()` init throws (Risk 1) — M1 polish.
- [ ] Wire `onTrimMemory()` to unload Gemma early under system pressure
      (PHASE1_PLAN §6) — M1 polish.
- [ ] Engineer to confirm power state + ambient temp for the run on record
      (Section 1) so the numbers can be reproduced.
- [ ] Sign off this memo. Distribute to the team.
