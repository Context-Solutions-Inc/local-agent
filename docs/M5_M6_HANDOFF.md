# M5 → M6 Handoff Note

**From:** M5 Phase F (2026-05-10)
**To:** M6 — Polish, eval, telemetry (WS-11 polish, WS-13 telemetry, WS-14
hosted CI, WS-15 release engineering)
**Status:** Ready

This is the operational handoff for the memory subsystem that landed in
M5. M6 doesn't extend the memory feature itself — the subsystem is
shipped — but several things M5 deferred should land before public
launch (M7). This note enumerates them so M6 has a working list.

---

## 1. Telemetry contract (WS-13) — counter-only, no memory text

PRD §4.4 + WS-12 forbid memory content in any telemetry payload. M5
landed the audit; M6 builds the telemetry pipeline around the
following constraints:

- The `memories` table and the `embedding` BLOBs are off-limits to the
  telemetry builder. Comment markers in `Memories.sq` and at
  `MemoryExtractor`'s logger callsite document this.
- The injected `logger`s on `MemoryRetriever` / `MemoryExtractor` /
  `MemoryEvictor` emit counts, IDs (UUIDs), accelerator names, or
  `text.length` only — never raw memory or user text. **Don't bridge
  these loggers into telemetry.** Use a separate counter API.
- Suggested counter shape (when WS-13 lands):

| Counter | Increment trigger |
|---|---|
| `memory_extracted_total` | per memory row inserted |
| `memory_dedup_skipped_total` | per `findCosineMatch` hit |
| `memory_forgotten_total` | per `deleteByCosine` hit |
| `memory_evicted_total{tier}` | per row deleted (tier=expired|stale|lru) |
| `memory_retrieved_total` | per non-empty `retrieveTopK` |
| `memory_retrieval_p95_ms` | rolling p95 of total retrieval time |
| `memory_creation_disabled_total` | per turn skipped via the toggle |

Aggregate over a 24-hour window before transmission. No memory IDs
(those are stable across an install — could re-identify); buckets
only.

---

## 2. Schema migration story

M5 added `access_count INTEGER NOT NULL DEFAULT 0` to the `memories`
table **without a `.sqm` migration file**. Existing dev installs from
M0/M1/M2/M4 require `pm clear` (or full uninstall) before the new
build runs successfully — documented inline in `Memories.sq`.

This is acceptable for closed beta (M7) but not for production unless
we want to wipe the install base. **M6 should:**

1. Add a `:shared/commonMain/sqldelight/migrations/` directory and the
   first `.sqm` file capturing the M0-shipped pre-`access_count`
   schema as version 1; bump `MobileAgentDatabase.Schema.version` in
   the .sq to 2.
2. Wire `AndroidSqliteDriver(Schema, context, name, MIGRATIONS)` so
   SQLDelight runs the delta on launch.
3. Add `verifyMigrations = true` in `:shared/build.gradle.kts`
   `sqldelight { databases { … } }` so future schema drift is caught
   at build time.
4. Re-run M5 Phase E on-device walkthrough to confirm migration
   succeeds when starting from an M2/M4 install.

This blocks public launch but does not block closed beta — beta users
clean-install.

---

## 3. Classifier recall improvement queue (v1.x)

The on-device review surfaced a real recall miss: phrasings like
"My mom's name is Jance" / "I have a sister named X" don't trigger
auto-extraction. v1.0 model card numbers explain why:

- Presence recall: 76.8% on test split.
- RELATIONSHIP category: 6.4% of training extractions — under-represented.

**Workaround surfaced to users:** `remember that …` prefix forces
extraction. Documented in the Phase E walkthrough.

**Real fix (v1.x dataset expansion):**
- Add ~500 more RELATIONSHIP exemplars to `memory_v1.x.0.jsonl`
  targeting common patterns ("my mom's name is X", "I have a brother
  named Y", "my best friend is Z").
- Add ~500 more PERSONAL_IDENTITY single-sentence disclosures.
- Re-train; run `ct-regression-check` to confirm no preflight
  regression; ship as `preflight_memory_shared_v1.x.y_int8.tflite`.

This is on the model card improvement queue (#4); already documented
there.

---

## 4. v1.x perf options surfaced during M5

Both deferred from M5 because the user-facing budgets are met:

### 4a. Pre-load embeddings to memory (cosine win)

Cosine over 1k entries lands at p95 = 31.87 ms on Pixel 7 — dominated
by SQLite BLOB → ByteArray JNI copy + per-row Memories class
materialisation, NOT the math itself. Pre-loading all embeddings into
a resident `Map<String, FloatArray>` on warm-up cuts cosine to <5 ms
at the cost of ~1.5 MB resident.

**Trigger condition:** PRD §3.2.4 retrieval p95 starts missing 100 ms
in real-user telemetry, OR users report stuttering on memory-heavy
chats. Currently end-to-end is 72 ms with 28 ms headroom.

**Implementation sketch:** add a `MemoryCacheLayer` decorator around
`SqlDelightMemoryStore` that mirrors inserts/deletes into an
in-memory map and reads cosine candidates from the map. Persistence
goes through the SQL layer unchanged.

### 4b. Embedder int32 input re-export (latency win)

Same v1.x option as the M4 classifier (model card #5). The MiniLM
graph as exported uses INT64 token-id inputs, which forces CPU
XNNPACK to the unoptimised scalar path. Re-exporting via
`classifier-training/scripts/export_minilm_litert.py --input-dtype
int32` (when that flag is added — currently only in
`ct-export-litert`) should give ~1.5-2× speedup.

**Trigger condition:** retrieval latency drifts above 100 ms p95 in
production telemetry, OR a future Pixel 8/9 GPU starts accepting the
int32 graph and we want the GPU path.

---

## 5. Auxiliary footprint — well under PRD budget

| Component | Size | Cumulative |
|---|---|---|
| Pre-flight + memory classifier (`preflight_memory_shared_v1.0.0_int8.tflite`) | 67.7 MB | 67.7 MB |
| Embedder (`all-MiniLM-L6-v2_int8.tflite`) | 23.5 MB | 91.2 MB |
| Vocab (`vocab.txt`, shared) | 0.2 MB | 91.4 MB |
| Preflight config JSON | <1 KB | 91.4 MB |
| **Total auxiliary footprint vs PRD §4.2 cap (200 MB)** | **91.4 MB ✓** | |

PRD §4.2 budgets 200 MB for auxiliary models. We're using 46% of that.
Plenty of room for a v1.x classifier upgrade or an additional small
on-device model (e.g., a span tagger for memory text generation
without going through Gemma).

---

## 6. Things M5 explicitly did NOT do (deferred)

- **Gemma-generated canonical memory text.** v1 stores verbatim user
  text; v1.x replaces via a brief background Gemma inference call per
  PRD §3.2.4. Memory `text` column rewrite plus a one-shot migration
  for existing rows.
- **In-place memory text editing.** Q4 deferred; delete-and-re-state
  is v1's workaround. v1.x adds an edit dialog that re-embeds the new
  text and rewrites the row in place.
- **Per-category disable toggle.** v1 has one global creation toggle.
  v1.x exposes per-category gates in `MemoryScreen` (e.g., "Don't
  remember temporary_context" for users who don't want trip planning
  history).
- **Multi-device memory sync.** Phase 2+. PRD §4.4 hard constraint —
  memories never leave the device in v1.
- **iOS embedder + memory store.** Stubs only in `:shared/iosMain/`,
  same as the M4 classifier path. Phase 2 wires the actuals.
- **Native vector index (HNSW/FAISS).** Brute force is fine at the
  1,000-row PRD cap. Re-evaluate if the cap moves.

---

## 7. v1 known weaknesses M6 should be aware of

| Issue | Severity | Where it bites |
|---|---|---|
| Classifier presence recall 76.8% — relationship-shaped facts often missed | Medium | "My mom's name is X" silently skipped; users discover via memory list being empty |
| Possessive substitution span heuristic is brittle | Low | Only matters when retrieval finds a memory AND the user query has a matching possessive AND the heuristic can't extract a clean tail. Falls back to RewriterAbort, which is the M4 behavior. |
| Schema migration not wired up | High pre-launch | Existing dev installs need `pm clear` before M5 builds. M6 fix above. |
| BLOB JNI cost dominates cosine | Low | Only matters at 1k+ memories on slow devices. v1.x pre-load fix above. |
| `temporary_context` expiry parser misses month-name absolute dates ("on May 17") | Low | Falls back to 30-day default. Acceptable noise. |

---

## 8. M5 deliverables M6 inherits

| Path | Purpose |
|---|---|
| `:shared/commonMain/memory/Memory.kt` | Data class; `EMBEDDING_DIM = 384` |
| `:shared/commonMain/memory/MemoryCategory.kt` | Enum mirroring schemas.py |
| `:shared/commonMain/memory/EmbedderEngine.kt` | Interface; lazy `warmUp` + `embed(text)` |
| `:shared/commonMain/memory/MemoryStore.kt` | DAO interface; brute-force cosine + eviction primitives |
| `:shared/commonMain/memory/SqlDelightMemoryStore.kt` | SQLite-backed impl |
| `:shared/commonMain/memory/MemoryRetriever.kt` | embed → cosine top-K |
| `:shared/commonMain/memory/MemoryExtractor.kt` | Detector/classifier paths + dedup + insert |
| `:shared/commonMain/memory/MemoryEvictor.kt` | 3-tier cascade |
| `:shared/commonMain/memory/RememberForgetDetector.kt` | Explicit-command regex |
| `:shared/commonMain/memory/TempContextDateParser.kt` | Best-effort date extractor |
| `:shared/commonMain/memory/MemoryPreferences.kt` | Toggle interface |
| `:shared/androidMain/memory/LiteRtEmbedderEngine.kt` | Android engine on `litert:2.1.4` |
| `:shared/androidMain/memory/SharedPreferencesMemoryPreferences.kt` | Toggle persistence |
| `:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/Memories.sq` | Schema |
| `:androidApp/src/main/assets/all-MiniLM-L6-v2_int8.tflite` | 23.5 MB embedder |
| `:androidApp/src/main/kotlin/.../app/ui/memory/MemoryViewModel.kt` | Backs both management + per-conv screens |
| `:androidApp/src/main/kotlin/.../app/ui/memory/MemoryScreen.kt` | Full inventory UI |
| `:androidApp/src/main/kotlin/.../app/ui/memory/ConversationMemoryListScreen.kt` | Per-chat slice |
| `:androidApp/src/main/kotlin/.../app/ui/memory/ConversationMemoryBadge.kt` | Chat top-bar badge |
| `:androidApp/src/main/kotlin/.../app/di/MemoryModule.kt` | Hilt providers |
| `classifier-training/scripts/export_minilm_litert.py` | Re-export driver |
| `docs/M5_PLAN.md` | Phase log + decisions + on-device review fixes |

---

## 9. Hooks M6 should add (not built in M5)

These are M6's call to make; they enable workflows M5 didn't strictly
need.

- **`MemoryStoreFlow` extension** — expose `Flow<List<Memory>>` from
  the SQLDelight `Query` so `MemoryViewModel` can observe rather than
  poll-on-entry. Today the screens call `refresh()` from
  `LaunchedEffect(Unit)`. Reactive updates become more important when
  M6 telemetry adds background mutations (e.g., backfilling memories
  from opt-in production data).
- **Conversation persistence.** ChatViewModel currently holds
  `agentHistory` in memory only. M6 / WS-11 polish should persist
  through the existing `messages` table so memories tagged with a
  conversation_id can be linked back to the chat that produced them.
- **Memory creation undo.** A 5-second snackbar after an insert with
  "Undo" → call `store.deleteById`. Matches the delete-confirmation
  affordance in `MemoryScreen` for symmetry.
