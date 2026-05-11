# Canonical routing + prompt eval

M6 Phase F regression gate for the agent's pre-Gemma decision surface.

**What it covers:** `PreflightRouter` band thresholds, `QueryRewriter`
date / possessive substitution, `PromptAssembler` system-prompt
structure (memory block, pre-flight notice, tool definition, behavior
guidelines). A change to any of these — or to `SYSTEM_PROMPT.md` — that
breaks an expected behavior on the canonical set blocks the PR via
[`.github/workflows/prompt-eval-gate.yml`](../../.github/workflows/prompt-eval-gate.yml).

**What it does NOT cover:** classifier numerical accuracy. That's the
job of `ct-regression-check` (Python-side, runs against the v1.0
regression JSONL via [`regression-gate.yml`](../../.github/workflows/regression-gate.yml)).
Phase F's canonical eval feeds the classifier hardcoded logits per
query so the routing layer is tested independently of the classifier
model.

**Why a Kotlin test (not a Python `ct-eval-prompt` driver as planned):**
the real classifier loads via `ai-edge-litert`, which is Android-only.
A Python port of `QueryRewriter` / `PreflightRouter` / `PromptAssembler`
doubles maintenance cost forever. Driving the production Kotlin code
directly from a host JVM test, with a fake classifier per query, gives
us regression detection without the duplication. The Python eval driver
remains a v1.x option if classifier retraining cadence picks up.

---

## File layout

```
eval/canonical/
├── README.md                       (this file)
└── baselines/                      (future — baseline JSON for the Kotlin test output)

android-app/androidApp/src/test/kotlin/com/contextsolutions/mobileagent/canonical/
└── CanonicalEvalTest.kt            the canonical set + assertions
```

The canonical query set lives **inside the Kotlin test file** rather
than as a separate YAML / JSON. Reasons:

- The set is small (~15 queries) and rarely edited by non-engineers.
- Kotlin data classes give us type-safety on `PromptBlock` / `MemoryCategory`
  enum values that a YAML file would re-encode as raw strings (and risk
  drift).
- The classifier-logits-per-query are pre-baked arrays; Kotlin's
  literal `floatArrayOf(...)` reads cleaner than a YAML approximation.
- Iteration is faster — adding a new canonical query is a Kotlin
  diff that compiles + runs in one step.

If a future need surfaces for non-engineer-editable canonical
queries, port the in-file set to YAML and load it from a test resource;
the schema would mirror `CanonicalEvalTest.CanonicalQuery`.

---

## Schema

Each canonical query is a `CanonicalEvalTest.CanonicalQuery` instance:

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Stable identifier, used in failure messages. Don't rename — failure logs grep by id. |
| `description` | `String` | Free-form, explains the intent (sport recent, memory-conditional, etc.). |
| `query` | `String` | The user-message text. |
| `memorySeed` | `List<Memory>` | Memories the router treats as "previously stored". Use the `memory(...)` helper. |
| `classifierLogits` | `FloatArray` | Raw `preflight_logits` (3 floats: `[search_required, search_not_required, ambiguous]`). Use the `HIGH_BAND_LOGITS` / `MIDDLE_BAND_LOGITS` / `LOW_BAND_LOGITS` constants where possible. |
| `searchAvailable` | `Boolean` | If false, the router short-circuits to `SearchDisabled`. |
| `expectedBand` | `String` | `"HighFireSearch"` / `"FallThrough_MiddleBand"` / `"LowSkipSearch"` / `"FallThrough_RewriterAbort"` / `"FallThrough_ClassifierUnavailable"` / `"SearchDisabled"`. |
| `expectedRewrittenContains` | `List<String>` | Tokens the rewritten query must contain (case-insensitive substring match). |
| `expectedPromptBlocks` | `Set<PromptBlock>` | System-prompt regions that MUST appear: `MemoryContext` / `PreflightNotice` / `ToolDefinition` / `CitationGuideline`. |
| `forbiddenPromptBlocks` | `Set<PromptBlock>` | System-prompt regions that MUST NOT appear (e.g., `PreflightNotice` for low-band queries). |

---

## Coverage targets (v1.0)

- **High band** (pre-flight fires): 4 queries spanning sports, markets,
  news, weather — each with at least one rewriter substitution (date
  or possessive).
- **Middle band** (Gemma decides): 3 queries that are intentionally
  ambiguous; assert the prompt does NOT carry a pre-flight notice but
  DOES include tool definitions.
- **Low band** (no search): 4 queries spanning settled history,
  definitions, coding, math.
- **Memory-conditional**: 3 queries that exercise possessive
  substitution with + without a memory seed (memory present = high-band
  with rewrite; memory absent = RewriterAbort).
- **Search disabled**: 1 query that confirms the `searchAvailable=false`
  short-circuit emits `SearchDisabled` regardless of classifier output
  and the prompt strips tool definitions.
- **System-prompt structure**: spot-checked across the set — every
  query asserts on at least one `expectedPromptBlocks` entry so a
  prompt structural regression surfaces in multiple cases.

---

## Adding a new canonical query

1. Edit `CanonicalEvalTest.kt`. Add a new `CanonicalQuery(...)` entry to
   `CANONICAL_SET`. Use a meaningful `id` you can `grep` for in failure
   logs.
2. Pick classifier logits matching the intended band (`HIGH_BAND_LOGITS`,
   `MIDDLE_BAND_LOGITS`, `LOW_BAND_LOGITS`). For non-canonical edge
   cases, hand-craft a `FloatArray`.
3. Run locally: `./gradlew :androidApp:testDebugUnitTest --tests com.contextsolutions.mobileagent.canonical.*`.
4. If the test passes, commit. The PR will run the same gate.
5. If the test fails: read the bundled error report. The assertion lists
   every regression in one block; fix the underlying code or adjust the
   expectation.

---

## Updating the canonical set version

`CanonicalEvalTest.CANONICAL_VERSION` defaults to `v1.0.0`. Bump it
when:

- A query is **removed** (vs. added or modified — those are non-breaking).
- The schema changes in a way that invalidates older `expectedBand`
  strings (e.g., adding a new `FallThroughReason`).
- A pre-existing query's `expectedBand` flips because the routing
  contract genuinely changed (not because of a bug).

Reflect the bump in `M6_PLAN.md` and in this README.

---

## Why classifier logits per query and not the real model

The real classifier is `ai-edge-litert`-backed, which is Android-only.
Loading it from a host JVM test would require either:
- Running on a real device or emulator (heavy infrastructure for what
  is fundamentally a routing-layer test).
- Porting the model loader to a host-CPU runner (already exists in
  `classifier-training/` but adds Python ↔ Kotlin coordination).

The canonical eval's purpose is **routing-layer regression detection**.
We accept that:

- Classifier accuracy regressions are caught by `ct-regression-check`.
- The canonical eval covers QueryRewriter / PreflightRouter /
  PromptAssembler in isolation.
- A real-device integration test (Phase G manual walkthrough) verifies
  the production code path end-to-end.

This separation lets the routing eval run in seconds on every PR,
without GPU or device infrastructure.
