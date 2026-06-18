# Model Card — `preflight_memory_shared_v1.0.0`

**Status:** v1.0 — frozen 2026-05-09 (M3 Phase H)
**Format:** LiteRT (.tflite), INT8 weight-only quantized
**Deployment:** on-device (Android), invoked via Play Services LiteRT
**License:** MIT (© 2026 Context Solutions Inc.)

---

## Intended use

Two on-device classifier heads sharing a single DistilBERT encoder, used by
the local-agent ReAct loop to short-circuit Gemma 4 invocations:

1. **Pre-flight search classifier** (M4 / WS-8) — given a user query, decide
   whether the agent should fire a web search before invoking Gemma 4, fall
   through to Gemma's own tool-call decision, or skip search entirely.
2. **Memory presence + category classifier** (M5 / WS-9, WS-10) — given a
   completed user/assistant turn, decide whether the exchange contains
   durable facts about the user worth saving as memory, and which of the 6
   `MemoryCategory` values they belong to.

Single tflite, single forward pass, three named outputs. The agent picks
whichever head is relevant for the active task.

---

## Artifact files

| File | Size | Purpose |
|---|---|---|
| `preflight_memory_shared_v1.0.0_int8.tflite` | 67.7 MB | Ship target — INT8 weight-only, channel-wise |
| `preflight_memory_shared_v1.0.0.tflite` | 264.6 MB | FP32 reference (debugging, A/B against INT8) |

Both files have an identical computation graph; the INT8 build dequantizes
weights at runtime via the LiteRT XNNPACK / GPU delegate.

---

## Architecture

| | |
|---|---|
| Base encoder | `distilbert-base-uncased` (HF Transformers 4.57.6) |
| Encoder params | 66.4 M (FP32) |
| Heads | preflight (Linear 768→3), memory_presence (Linear 768→2), memory_category (Linear 768→6 multi-label sigmoid) |
| Dropout | 0.1 (heads only; encoder uses HF default) |
| Sequence length | 128 tokens (static, set at export time) |
| Tokenizer | `distilbert-base-uncased` AutoTokenizer (lowercased, WordPiece) |

Source: `classifier_training/training/model.py::SharedEncoderTwoHeads` and
its `ExportableSharedEncoder` wrapper.

---

## Training data

| Dataset | Version | Examples | Regression SHA-256 |
|---|---|---|---|
| Pre-flight | `preflight_v1.0.0` | 11,670 | `9724f57840a4fd73ebdc318911ce7a79c6b43d3c093e314983538350521be544` |
| Memory | `memory_v1.0.0` | 7,707 | `7801cb2386dd8f72fd1ffefb2b737a47988e7aac81bf322e1507db72d4c94ae6` |

Generated synthetically via local Ollama (`qwen3.5:9b`) with hand-authored
adversarial pair prototypes (80 preflight pairs + 48 memory hard cases —
see `classifier-training/src/classifier_training/generation/`). Distribution
snapshots are embedded in
`datasets/preflight/MANIFEST.md` and `datasets/memory/MANIFEST.md`.

The CI gate (M4 WS-14) reads the regression SHA-256 from these manifests
and refuses any classifier update that produces different scores on the
locked regression rows.

---

## Training hyperparameters

| | |
|---|---|
| Optimizer | AdamW |
| Learning rate | 2e-5 |
| Weight decay | 0.01 |
| Batch size | 32 |
| Epochs | 5 |
| LR schedule | linear warmup 6% → linear decay |
| Loss | per-task CE + multi-label BCE (memory category), equal weights |
| Multi-task batching | proportional dataset-size sampling (preflight + memory interleaved) |
| Seed | 42 |
| Hardware | RTX 5090 Laptop, 24 GB VRAM |
| Wall clock | ~6 min for 5 epochs (full multi-task) |
| Best val checkpoint | epoch 2 (val macro-F1 0.728 on preflight) |

Reproducible via:

```bash
ct-train-classifier \
    --preflight-jsonl datasets/preflight/preflight_v1.0.0.jsonl \
    --memory-jsonl    datasets/memory/memory_v1.0.0.jsonl \
    --output-dir      eval/runs/<timestamp> \
    --epochs 5 --batch-size 32 --lr 2e-5 \
    --weight-decay 0.01 --warmup-pct 0.06 --grad-clip 1.0 --seed 42
```

---

## Evaluation summary (FP32 ship checkpoint)

Detailed report: `eval/runs/phaseF_full_20260509_162556/REPORT.md`

### Pre-flight (test split, 983 examples)

| Metric | Value | PRD §7 target | Status |
|---|---:|---:|---|
| Accuracy | 80.0% | — | informational |
| Macro-F1 | 0.692 | — | informational |
| High-band precision (>0.85) | 88.6% | ≥95% | ✗ -6.4pp |
| Time-sensitive recall (per-class argmax) | 86.8% | ≥90% | ✗ -3.2pp |
| Adversarial-pair accuracy | 83.7% | — | informational |

Per-class:

| Class | Precision | Recall | F1 |
|---|---:|---:|---:|
| search_required | 0.746 | 0.868 | 0.802 |
| search_not_required | **0.957** | 0.861 | 0.906 |
| ambiguous | 0.387 | 0.350 | 0.368 |

Pre-flight (regression split, 699 examples) — same shape, slightly stronger:
high-band precision 0.908, time-sensitive recall 0.853, adversarial-pair
accuracy 88.0%.

### Memory (test split, 620 examples)

| Metric | Value | PRD §7 target | Status |
|---|---:|---:|---|
| Presence precision | 92.2% | ≥90% | ✓ |
| Presence recall | 76.8% | — | informational |
| Presence F1 | 83.8% | — | informational |
| Category macro-F1 | 0.435 | — | informational |
| Forget-command accuracy | 100.0% | — | ✓ |
| Remember-command accuracy | 90.2% | — | ✓ |

Memory (regression split, 367 examples): presence precision 96.2%, forget
100%, remember 91.3%.

### Latency

**Real Pixel 7 measurement (M4 Phase B, 2026-05-10)** via
`ClassifierLatencyBenchmark` (200 warmup + 1,000 measured passes through
`ai-edge-litert:2.1.4` over 16 mixed canonical queries):

| Build | Accelerator | p50 | p95 | p99 | mean | M4 gate |
|---|---|---:|---:|---:|---:|---|
| **INT8 .tflite (ship)** | **CPU XNNPACK** | **112.1 ms** | **113.5 ms** | **114.6 ms** | **112.3 ms** | <150 ms ✓ |

GPU acceleration was attempted but failed at compile time: `ai-edge-litert`'s
GPU delegate (and Play Services TFLite's GPU delegate before it) refuse the
exported graph because of unsupported ops — `BROADCAST_TO`,
`EMBEDDING_LOOKUP`, `CAST INT64→FLOAT32`. CPU XNNPACK delegated 345/353
ops (97.7%); the remaining 8 fall through to the reference CPU runtime.

The PRD §2.3 80 ms target was extrapolated from a 14.7 ms host-CPU proxy
assuming a 3-4× Tensor G2 slowdown. Reality is ~8× — XNNPACK's INT64 input
paths don't hit the optimized kernels. **The 33 ms gap from the 80 ms aspiration
is acceptable for v1**: pre-flight still saves a 2-3 second Gemma round-trip
on high-band queries, so 113 ms is net-positive on user-facing latency. v1.x
improvement queue (#6 below) tracks the int32-input re-export that should
recover the 80 ms target.

Reference host-CPU proxy (kept for tracking ratio drift across releases):

| Build | p50 | p95 | p99 |
|---|---:|---:|---:|
| FP32 .tflite (host CPU) | 37.8 ms | 46.9 ms | 48.8 ms |
| INT8 .tflite (host CPU) | 11.4 ms | 14.7 ms | 16.0 ms |

---

## Threshold defaults (configurable per PRD §3.2.1)

| Band | Threshold | Action |
|---|---:|---|
| High | `p_search_required > 0.85` | fire pre-flight search before Gemma 4 |
| Low | `p_search_required < 0.15` | skip search; route directly to Gemma 4 |
| Middle | otherwise | fall through to Gemma 4's own tool-call decision |

The thresholds are configurable via the agent's shipped JSON config. Post-launch
tuning via telemetry is the documented path to closing the §7 gap.

For memory presence, the default decision threshold is `argmax(presence_logits)` —
no calibration band; the agent extracts iff the binary head predicts class 1.

---

## Known weaknesses (v1.0)

1. **Pre-flight high-band precision short of 95%.** No threshold in [0.50, 0.95]
   on the FP32 model achieves 95% precision on the test split — caps at 0.905
   at threshold 0.95. The dataset has inherent boundary noise between
   `search_required` and `ambiguous`. INT8 quantization actually *improves*
   precision (different Pareto point: ship_threshold=0.92 → 0.956 precision),
   at the cost of recall.
2. **Pre-flight time-sensitive recall (per-class argmax) at 86.8%, target 90%.**
   The `ambiguous` class predictions absorb ~11% of true `search_required`
   examples. The middle band still routes those to Gemma's tool-call
   decision, so the user-facing failure rate is lower than the metric
   suggests, but the gap is real.
3. **Confidence calibration: low-confidence under-represented.** Training data
   has 1.4% confidence=low vs §2.2's 5% target. Model rarely produces
   middle-band predictions for search_required. Mitigation: targeted
   `--target-confidence low` top-up batch in v1.x if telemetry shows
   middle-band routing under-performs.
4. **Memory density skew: multi over-represented.** v1.0 dataset has 22.6%
   multi-memory turns vs §3.2's 12% target. The multi exemplar in the
   memory generation prompt leaks into other density batches. Could be
   addressed by subsampling multi rows pre-training in v1.x.
5. **Memory category macro-F1 at 0.435.** Category multi-label is harder than
   binary presence; long-tail categories (relationship, temporary_context)
   at 6.4% / 5.9% of extracted memories drag macro-F1 down. v1.x: more
   data in those categories, or simplify to single-label by category
   prediction.
6. **Naturalistic phrasings 28.1% (target ≥30%).** Drifted down 2pp during
   Phase C pair expansion since rephrasings skew formal. Acceptable for
   v1.0; worth re-tuning the expansion prompt in v1.x if classifier
   under-performs on real telemetry queries.
7. **Pixel 7 latency 113 ms p95 vs 80 ms PRD §2.3 aspiration.** The
   exported graph uses INT64 token-id inputs and includes `BROADCAST_TO` /
   `EMBEDDING_LOOKUP` / `CAST INT64→FLOAT32` ops that prevent any GPU
   delegate from compiling, forcing CPU XNNPACK. XNNPACK's INT64 paths
   are unoptimized scalar code. Mitigation in v1.x: re-export with int32
   inputs via `ct-export-litert --input-dtype int32`; expected to recover
   ~1.5-2× speedup and clear the 80 ms target.

All of these are tracked as v1.x improvement opportunities in
`docs/M3_PLAN.md` Phase H follow-ups.

---

## Failure modes

- **GPU delegate unavailable** (older Play Services). Falls back to CPU XNNPACK
  — host-proxy estimate ~45 ms p95 on Pixel 7, still under target.
- **Tokenizer drift.** The Android side MUST use the same DistilBERT
  vocabulary as training. Any drift (different cased/uncased, different
  sub-word splits) silently degrades quality. The handoff note specifies
  the exact vocabulary version; M4 verifies via test fixture.
- **Sequence length mismatch.** The .tflite is fixed-shape at seq_len=128.
  Queries longer than ~50 tokens after sub-word tokenization are truncated;
  if real queries are dramatically longer, re-export with larger seq_len.

---

## Reproducibility

- Dataset versioning: `preflight_v1.0.0` / `memory_v1.0.0` regression-split
  SHA-256s above. Any change to the regression rows requires a major version
  bump and a fresh classifier re-evaluation.
- Training: `ct-train-classifier` with the hyperparameters above + seed 42.
- Quantization + export: `ct-quantize` for the PyTorch INT8 baseline (CPU eval),
  `ct-export-litert --int8` for the shipped .tflite (channel-wise weight-only via
  ai-edge-quantizer).

---

## Privacy and safety

- Trained 100% on synthetic data (memory is synthetic-only per
  CLASSIFIER_DATASETS.md §4.3).
- No real user data in the v1.0 training set.
- The classifier outputs probabilities only — no text generation, no
  personally-identifying information emitted.
- Real user queries enter the dataset only via opt-in telemetry post-launch
  (M6 / WS-13), with PII regex pre-filtering per §4.3.

---

## v1.x improvement path

In rough priority order:

1. Telemetry-driven dataset expansion targeting the search_required /
   ambiguous boundary (the dominant cause of the 6.4pp precision gap).
2. Confidence-low top-up batches if Phase F+ eval on real queries shows
   middle-band routing pathologies.
3. Subsample memory multi rows or re-balance density at training time.
4. Re-tune `prompts/preflight_pair_expansion.j2` to demand more naturalistic
   variants (currently formal-skewed).
5. **Re-export with int32 token-id inputs** (`ct-export-litert --input-dtype int32`)
   to clear the 80 ms PRD §2.3 latency target. v1.0 ships at 113 ms p95 on
   Pixel 7 CPU because INT64 inputs miss XNNPACK's optimized paths and the
   GPU delegate refuses the graph. Pursue when post-launch telemetry shows
   pre-flight latency contributing to user-perceived delay.
6. Stretch: replace DistilBERT-base with DeBERTa-v3-base (better boundary
   handling) — exceeds §4.2 50 MB classifier budget; revisit when memory
   headroom permits.

---

## Contact

Context Solutions Inc. — `info@contextsolutions.ca` — Phase 1 architect.
