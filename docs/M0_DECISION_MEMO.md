# M0 Decision Memo — Pixel 7 Inference Envelope

**Status:** TEMPLATE — fill in after the WS-1 spike runs on real hardware
**Owner:** TBD
**Target completion:** end of week 3
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

Fill in after running:

| | Value |
|---|---|
| Device | Google Pixel 7 (model number ___) |
| Android version | (e.g. 16.0.0, build ___) |
| LiteRT-LM version | |
| Model artifact | (path, size, SHA256) |
| KV cache tokens | 8,192 |
| Canonical prompt set | `CanonicalPrompts.ALL` (5 prompts, see SpikeRunner.kt) |
| Power state during run | (plugged in / battery, screen on / off) |
| Ambient temperature | |
| Date/time of run | |
| Run JSON path | `filesDir/spike-results/spike-<runId>.json` |

---

## 2. Measurements

Copy from the run JSON's `summary()` output and per-prompt rows.

### 2.1 First-token latency

| Prompt | Cold load? | First token (ms) |
|---|---|---|
| short_factual | yes | |
| medium_explanation | no | |
| long_creative | no | |
| long_technical | no | |
| sustained_5min | no | |

| Aggregate | Phase 1 target | Measured |
|---|---|---|
| First token p50 | <2,000 ms (PRD) / <4,000 ms (Phase 1 Pixel 7) | |
| First token p95 | <2,500 ms (PRD) / <4,000 ms (Phase 1 Pixel 7) | |

### 2.2 Sustained throughput

| Prompt | Tokens generated | Duration (ms) | tok/s |
|---|---|---|---|
| short_factual | | | |
| medium_explanation | | | |
| long_creative | | | |
| long_technical | | | |
| sustained_5min | | | |

| Aggregate | Phase 1 target | Measured |
|---|---|---|
| Sustained mean | ≥15 (PRD) / ≥8 (Phase 1 Pixel 7) | |

### 2.3 Memory

| | Target | Measured |
|---|---|---|
| Cold-load peak RSS | < 4 GB (PRD §4.2) | |
| Sustained-generation peak RSS | < 4 GB | |
| Native heap peak | (informational) | |

### 2.4 Thermal

| | |
|---|---|
| Thermal status at start | |
| Thermal status at end | |
| Peak observed during sustained_5min | |

`THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6`

### 2.5 Cold load

| | Target | Measured |
|---|---|---|
| Time to load model (cold) | 4–8 s (Phase 1 Pixel 7) | |

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

### Decision 2 — Accelerator path

- [ ] **NPU (Tensor G2 EdgeTPU).** LiteRT-LM delegate works and gives meaningful speedup.
- [ ] **GPU (Mali-G710 via OpenCL/Vulkan).** NPU delegate unavailable or slower than GPU.
- [ ] **CPU only.** Neither NPU nor GPU acceleration available — implies E2B downgrade.

Rationale: _(fill in)_

### Decision 3 — Foreground service contract

- [ ] **`specialUse` works as specified in `AndroidManifest.xml`.**
- [ ] **Need to revisit type / declaration.** _(describe)_

Rationale: _(fill in)_

### Decision 4 — KV cache size

- [ ] **8,192 tokens (PRD default) is feasible.**
- [ ] **Reduce to ___ tokens.** Memory pressure forces it.

### Decision 5 — Idle unload aggressiveness

- [ ] **5-minute idle unload (PRD default) is sufficient.**
- [ ] **Need more aggressive (___ minutes) or onTrimMemory()-driven unload.**

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

_(Fill in any new risks discovered during the spike that are not already in
PHASE1_PLAN.md §7. Update the plan with mitigations.)_

---

## 6. Next steps

- [ ] Update `PHASE1_PLAN.md §2.3` with measured perf targets (replace placeholders).
- [ ] If E2B chosen, update PRD § references and resize memory budget table.
- [ ] If accelerator path forces it, add `Accelerator` enum default override in `InferenceConfig`.
- [ ] Sign off and freeze this memo. Distribute to the team.
