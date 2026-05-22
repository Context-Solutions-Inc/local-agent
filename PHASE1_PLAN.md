# Phase 1 Implementation Plan — Android On-Device AI Assistant

**Document version:** 0.1 (Draft)
**Status:** Awaiting review
**Last updated:** 2026-05-03
**Companion to:** PRD.md, SYSTEM_PROMPT.md, CLASSIFIER_DATASETS.md, agent_loop_state_diagram.svg

---

## 1. Phase 1 scope

Phase 1 delivers the **complete v1 product described in PRD.md**, restricted to a single platform and device class, and ships it to a **public Play Store launch**.

| Dimension | Phase 1 commitment |
|---|---|
| Platforms | Android only (iOS deferred to Phase 2) |
| Devices | Google Pixel 7 (non-Pro, non-a) — 8 GB RAM, Tensor G2, ARM64 |
| OS floor | Android 16 (API 36) |
| Feature scope | Full v1 PRD: chat, web search, pre-flight classifier, memory subsystem, telemetry pipeline |
| Models | Gemma 4 E4B Q4 (per PRD), accept relaxed perf targets vs the PRD's Pixel 9 Pro reference |
| Distribution | Public Play Store production track |
| Inference runtime | LiteRT-LM (per PRD section 2.1, 5.3) |

Anything not in PRD section 9 ("Out of scope") is in scope. Anything in PRD section 9 stays deferred.

---

## 2. Target hardware envelope

Pixel 7 is materially more constrained than the PRD's reference Pixel 9 Pro, so the memory and performance budgets need to be redrawn before any architecture is locked.

### 2.1 Hardware

- **SoC:** Google Tensor G2 (older than Tensor G3/G4 referenced for Pixel 9)
- **RAM:** 8 GB (vs 12 GB on Pixel 9 Pro)
- **GPU:** Mali-G710 MC7
- **NPU:** Tensor G2 EdgeTPU — LiteRT delegate availability needs validation in the M0 spike
- **Storage:** UFS 3.1 — model download/load speed will be acceptable
- **Display:** 1080×2400, 90Hz

### 2.2 Memory budget (revised after M0 spike)

The original plan budgeted for E4B; M0 demonstrated E4B doesn't fit on Pixel 7
(LMKD-induced thrash). Switched to E2B per `docs/M0_DECISION_MEMO.md` Decision 1.
Updated table:

| Component | Footprint | Notes |
|---|---|---|
| Gemma 4 E2B weights | ~2.58 GB | `litert-community/gemma-4-E2B-it-litert-lm`, on-disk |
| KV cache (8K context) | included above | LiteRT-LM 0.10.2 mmaps + decode in one allocation |
| Pre-flight classifier | ~50 MB | INT8, MobileBERT/DistilBERT class |
| Memory extraction classifier | ~50 MB | Same architecture, separate head |
| Sentence embedder (MiniLM) | ~25 MB | INT8, per PRD section 3.2.4 |
| App + Compose + DB | ~150 MB | |
| **M0-measured peak PSS during sustained generation** | **3.52 GB** | Spike run `ca42ff6a`, sustained_5min prompt |

PRD section 4.2 caps app memory at 4 GB. On an 8 GB device with OS + other apps consuming 3–4 GB, this is at the edge of what the OS will tolerate before triggering low-memory kills. The M0 spike confirmed E2B leaves ~480 MB of headroom under the 4 GB ceiling — workable but not generous. **Aggressive idle unloading of Gemma (PRD's 5-minute default) is not optional on Pixel 7 — it is the load-bearing mechanism that keeps the app running across multi-app workflows.** `onTrimMemory()`-driven proactive unload (M1 polish) provides belt-and-suspenders coverage when other apps surge.

### 2.3 Performance targets — measured in M0 spike

PRD section 4.1 targets (Pixel 9 Pro): first token <2 s, sustained ≥15 tok/s, end-to-end search query <12 s p90.

| Metric | PRD (Pixel 9 Pro) | Phase 1 (Pixel 7) target | **M0 measured (Pixel 7 + E2B + GPU)** |
|---|---|---|---|
| First token p50, no tool call | <2.0 s | <4.0 s | **0.55 s** ✅✅ |
| First token p95, no tool call | <2.5 s | <4.0 s | **0.93 s** ✅✅ |
| Sustained generation (mean) | ≥15 tok/s | ≥8 tok/s | **13.5 tok/s** ✅ |
| Sustained generation (longest prompt) | — | — | 11.6 tok/s |
| End-to-end with one search | <12 s p90 | <16 s p90 | (M2 — needs Brave wired) |
| Pre-flight classifier inference | <50 ms p95 | <80 ms p95 | **113 ms p95** (M4 Phase B; 80 ms aspiration deferred to v1.x int32 re-export — model card v1.x #5) |
| Memory retrieval (embedding + cosine) | <100 ms p95 | <150 ms p95 | (M5) |
| Cold-start model load | 2–4 s | 4–8 s | **4.3 s** ✅ |
| Peak PSS, sustained generation | <4 GB | <4 GB | **3.52 GB** ✅ |
| Thermal max under 1.8 min sustained | <SEVERE (3) | <SEVERE (3) | **MODERATE (2)** ✅ |

We're beating the relaxed Pixel 7 targets across the board on inference perf
alone, and approaching the original Pixel 9 Pro targets. Search/classifier/
memory targets remain to be measured as those subsystems land.

Source: spike run `ca42ff6a-6911-4ad3-84d1-29f264ad8ed2`, 2026-05-05. See
`docs/M0_DECISION_MEMO.md` for full per-prompt detail.

---

## 3. Architecture

### 3.1 Module layout

```
/shared                       # Kotlin Multiplatform module
  /commonMain
    /agent                    # Agent loop, ReAct, prompt assembly, tool dispatch
    /classifier               # Pre-flight + memory extractor common interfaces
    /memory                   # Store, retrieval, extraction, dedup, eviction
    /search                   # Brave client, post-processing, cache
    /storage                  # SQLDelight schemas + DAOs
    /platform                 # expect declarations: clock, locale, secure storage, fs, http engine
  /androidMain                # actuals: JNI to LiteRT-LM, OkHttp Ktor engine, EncryptedSharedPrefs
  /iosMain                    # stubbed in Phase 1; concrete in Phase 2

/androidApp                   # Kotlin + Jetpack Compose shell
  /ui                         # Chat, conversation list, settings, memory mgmt, onboarding
  /di                         # Hilt modules
  /service                    # Foreground service for inference, WorkManager for downloads

/classifier-training          # Python: dataset gen, fine-tuning, quantization, eval
/datasets                     # JSONL files, semantic-versioned (gitignored payloads, manifests committed)
/models                       # gitignored runtime artifacts
/eval                         # Regression harness, canonical query sets, CI gates
```

`iosMain` exists as an empty stub from day 1 so the expect/actual contracts are exercised. Phase 2 fills it in without restructuring.

### 3.2 Key dependencies

- **Inference:** LiteRT-LM Android AAR via JNI; pinned to a specific release tag and isolated behind a single `InferenceEngine` interface in `commonMain` (the only place LiteRT API churn touches us).
- **Async/storage:** kotlinx.coroutines, SQLDelight (Android SQLite driver), Ktor with OkHttp engine.
- **Serialization:** kotlinx.serialization for tool schemas, Brave responses, telemetry payloads.
- **Android-only:** Jetpack Compose, Hilt DI, WorkManager (model download), EncryptedSharedPreferences (Brave key), AndroidX Security crypto.
- **Classifier training (off-device, Python):** HuggingFace Transformers, ONNX/TFLite conversion toolchain, LiteRT conversion utilities.

### 3.3 Inference integration pattern

The shared layer exposes one interface and never imports LiteRT types:

```
interface InferenceEngine {
    suspend fun loadModel(path: Path, kvCacheTokens: Int): ModelHandle
    fun unload(handle: ModelHandle)
    fun generate(handle: ModelHandle, prompt: Prompt): Flow<GenerationEvent>
    // GenerationEvent: TokenChunk | FunctionCall | Done | Error
}
```

Function-call parsing is implemented once in `commonMain` over the token stream. The Android `actual` is a thin JNI wrapper that calls into the LiteRT-LM C++ runtime and emits chunks back via a Kotlin Flow.

---

## 4. Workstreams

Workstreams parallelize. Dependencies are noted; everything else can run concurrently.

| ID | Workstream | Owner profile | Depends on |
|---|---|---|---|
| WS-1 | Inference foundation (LiteRT-LM + Gemma 4 E4B + model download + streaming + thermal/idle mgmt) | Android + native | — |
| WS-2 | Shared KMP scaffolding (modules, expect/actual, SQLDelight schemas, Ktor) | KMP/Android | — |
| WS-3 | Agent loop & prompt assembly (per SYSTEM_PROMPT.md and agent_loop_state_diagram.svg) | KMP/agent | WS-1, WS-2 |
| WS-4 | Web search via Brave (client, post-processing, cache, BYOK + dev key) | KMP/agent | WS-2 |
| WS-5 | **Pre-flight classifier dataset** (12k examples per CLASSIFIER_DATASETS.md §2) | ML + labeling lead | — |
| WS-6 | **Memory extraction dataset** (8k examples per CLASSIFIER_DATASETS.md §3) | ML + labeling lead | — |
| WS-7 | Classifier training & LiteRT export (base-model selection, fine-tune, INT8 quant, on-device benchmark) | ML | WS-5, WS-6 |
| WS-8 | Pre-flight integration (classifier inference in shared, three-band routing, query rewriter, prompt block) | KMP/agent | WS-3, WS-7 |
| WS-9 | Memory store & retrieval (embedder integration, SQLite vector storage, cosine retrieval, dedup, eviction) | KMP/agent | WS-2, WS-7 |
| WS-10 | Memory extraction (post-turn background job, candidate templating, explicit remember/forget commands) | KMP/agent | WS-7, WS-9 |
| WS-11 | Android UI (Compose chat with streaming, conversation list, settings, memory mgmt, onboarding, search status, citations) | Android | WS-2 (mock InferenceEngine until WS-1) |
| WS-12 | Storage & security (EncryptedSharedPreferences for Brave key, file-based encryption verification, log scrubbing) | Android | WS-2 |
| WS-13 | Telemetry pipeline (opt-in classifier-improvement aggregates, off by default, no content) | Backend-light + Android | WS-8 |
| WS-14 | Evaluation harness (regression sets, canonical query suites, CI gating for classifier and prompt updates) | ML + QA | WS-7 (for classifier), WS-3 (for prompt) |
| WS-15 | Release engineering (signing, Crashlytics with content scrubbing, Data Safety form, privacy policy, store listing, internal/closed/open tracks) | Android + PM | WS-11 mature enough to ship |

---

## 5. Milestones & sequencing

Sequencing is roughly six months end-to-end with a small team (sizing in §9). Calendar weeks are relative to project kickoff.

### M0 — Foundation & spike (weeks 1–3) ✅ COMPLETE 2026-05-05

- ✅ **WS-1 spike:** LiteRT-LM 0.10.2 stood up on Pixel 7 + Android 16 (SDK 36).
  E4B ruled out by LMKD-induced thrash; switched to E2B
  (`litert-community/gemma-4-E2B-it-litert-lm`). GPU works via Play Services
  TFLite OpenCL delegate; NPU not exposed to apps. Numbers in
  `M0_DECISION_MEMO.md` §2; perf targets ratified in §2.3 above.
- ✅ **WS-2:** KMP scaffolding, Gradle 9.3.1 + AGP 9.1.1 + Kotlin 2.3.21,
  expect/actual contracts in place, SQLDelight schemas defined, iosMain stubs
  exist for Phase 2.
- ✅ **WS-5/6 kickoff:** JSON schemas + Pydantic validators committed,
  frontier-model generation prompts in `classifier-training/prompts/`,
  Argilla selected as labeler tool with `ct-argilla-init` ready.
- ✅ **Deliverable:** Decision memo populated (4 of 5 decisions ratified;
  Decision 3 — foreground service contract — deferred to M1 since the
  spike's Activity surface doesn't exercise it). M1 unblocked.

Outstanding M0 items, status as of 2026-05-08:
- ✅ Decision 3 (foreground service contract) — VALIDATED 2026-05-05 via M1 WS-1
  Phase C exit-gate Drill 9 (see `docs/M0_DECISION_MEMO.md` §3 Decision 3).
- ✅ 16 KB native page alignment check on `liblitertlm_jni.so` — VERIFIED
  2026-05-05 (`docs/M0_DECISION_MEMO.md` §4). All three LOAD segments
  16 KB-aligned in the 0.10.2 AAR; re-run after any LiteRT-LM bump.
- [ ] Power state / ambient temperature for the on-record run (engineer to confirm).

### M1 — Chat MVP (weeks 3–8)

**WS-1 status (2026-05-05):** ✅ Complete. All 12 exit-gate drills passed on
Pixel 7. The full path runs on Pixel 7 + Android 16: first-run downloads
Gemma 4 E2B (resumable, SHA-256 verified, atomic rename), MainScreen routes
to a one-shot test-chat surface, `InferenceSessionManager` cold-loads on
first prompt, streams tokens, and unloads after 5 minutes idle. Decision 3
(foreground service contract) is satisfied — generation survives Home-button
backgrounding. Three on-device
fixes were necessary beyond the original design: (a) `tools:node="merge"`
override on WorkManager's `SystemForegroundService` to declare
`foregroundServiceType="dataSync"` (without it, every foreground worker crashes
the worker process); (b) runtime `POST_NOTIFICATIONS` request in MainActivity
(without it Android 13+ silently drops every notification); (c) honest "Queued"
copy because `WorkInfo.ENQUEUED` collapses both "waiting on network" and
"waiting on backoff" into one state. WS-2/3/11 not yet started.

- **WS-1:** Production LiteRT-LM integration. Model download via WorkManager with checksum verification, pause/resume, metered-network confirmation. Idle unload at 5-minute default. Foreground service with `specialUse` type during active generation.
- **WS-2:** SQLDelight schemas for `conversations`, `messages`, `memories`, `search_cache`, `telemetry_aggregate`.
- **WS-3:** Static system prompt (no memory or pre-flight blocks yet), basic agent loop with no tools, conversation history sliding-window truncation.
- **WS-11:** Compose chat UI with token streaming, conversation list, basic settings shell, onboarding skeleton (consent → key entry → model download).
- **Deliverable:** A user can open the app on a Pixel 7, complete first-run, download Gemma 4, and have a streamed chat conversation. No search, no memory.

#### M1 WS-1 manual exit-gate checklist

These are the on-device drills WS-1 must pass before M2 begins. They double as
the validation for `M0_DECISION_MEMO.md` Decision 3 (foreground service contract).
Run on a real Pixel 7 with `MODEL_SHA256` and `MODEL_SIZE_BYTES` filled in.

| # | Drill | Expected behaviour | Status |
|---|---|---|---|
| 1 | Fresh install → tap "Download (WiFi only)" on WiFi | Progress notification appears; UI shows %; completes; routing flips to chat. | ✅ 2026-05-05 |
| 2 | Mid-download: airplane mode on, then off | Worker fails with retryable error; WorkManager backs off; resumes from existing offset (Range header). | ✅ 2026-05-05 |
| 3 | Mid-download: tap Pause, then Download again | Worker is cancelled; partial sticks around; re-enqueue picks up at offset. | ✅ 2026-05-05 |
| 4 | Corrupt the partial file (e.g. via `adb shell run-as ... echo > .partial`), re-enqueue | Worker fails with CHECKSUM; partial is deleted; UI shows the friendly checksum error. | ✅ 2026-05-05 |
| 5 | Free storage below model size + 200 MB before download | Worker fails fast with STORAGE; UI shows "not enough free storage". | ✅ 2026-05-05 |
| 6 | First chat prompt after fresh load | Model cold-loads in 4–8 s; first token follows; banner shows "Loaded on GPU". | ✅ 2026-05-05 |
| 7 | Second prompt within 5 minutes | No reload; same banner. | ✅ 2026-05-05 |
| 8 | Wait > 5 minutes idle, then send a prompt | Banner shows Unloaded → Loading → Loaded; reload takes 4–8 s. **Decision 5 validation.** | ✅ 2026-05-05 |
| 9 | Send a prompt, press Home mid-generation | FGS notification stays; generation completes in background; coming back to app shows the full response. **Decision 3 validation.** | ✅ 2026-05-05 |
| 10 | Tap "Unload" debug button mid-generation | `forceUnload` is deferred; generation completes; model unloads immediately afterwards. | ✅ 2026-05-05 |
| 11 | Toggle GPU off (e.g. simulate via test build with `Accelerator.CPU` pinned) | `LiteRtInferenceEngine.tryInitialize` falls back; banner shows "CPU — degraded mode". | ✅ 2026-05-05 |
| 12 | Run the M0 spike harness (Spike action in chat top bar) | Spike still works; M0 numbers reproduce within noise. | ✅ 2026-05-05 |

Failures on any of 1–9 block M2. 10–12 are nice-to-have for confidence.

### M2 — Web search & full agent loop (weeks 6–10) ✅ COMPLETE 2026-05-07

End-to-end chat with web search via Gemma's tool calls is working on Pixel 7. M2
also absorbed M1's WS-2/3/11 (the M1 milestone shipped only WS-1; the rest was
rolled into the M2 PR sequence).

- ✅ **WS-2 schemas:** `search_cache` extended with `last_accessed_at_epoch_ms`
  + LRU eviction query for the 500-entry cap (PRD §3.4); `messages.tool_call_json` /
  `tool_result_json` columns already present from M0.
- ✅ **WS-4:** Brave Search client over Ktor (`KtorBraveSearchClient`),
  post-processing per PRD §3.3 (top-5 organic, ≤200 char snippets, ≤4KB total
  with progressive shrink). `SearchCacheDao` with category-based TTL +
  expired-row sweep on store + LRU evict; cache-hit indicator threaded through
  `SearchOutcome.fromCache`. `BraveKeyProvider` resolves user-key (BYOK) ahead
  of `BuildConfig.BRAVE_DEV_KEY` for internal builds.
- ✅ **WS-3:** ReAct loop is now the documented LiteRT-LM 0.10.2 pattern — one
  `Conversation` per user turn, multiple `sendMessageAsync` calls on it.
  `AgentLoop` provides a `ToolDispatcher` callback; the engine drives the
  multi-step cycle internally on a single conversation. Per-turn cap (3 calls)
  enforced in the dispatcher with a "limit reached" tool response. Citations
  accumulate across the turn into the final `ChatMessage.Assistant`.
- ✅ **WS-11:** `ChatScreen` rewritten — alternating user/assistant bubbles,
  live streaming, "Searching: \<query\>" chip, citation chips with
  `Intent.ACTION_VIEW` browser deep-links, cache-hit indicator, auto-scroll.
  `SettingsScreen` with Brave key entry (masked save/clear/reveal),
  search-disable toggle, cache-clear with live entry count. Three-state
  navigation in `MainScreen` (download → chat → settings) via `BackHandler`.
- ✅ **WS-12:** `EncryptedSharedPreferences` wired through `BraveKeyProvider`.
  OkHttp logging interceptor scrubs `Authorization` / `X-Subscription-Token`
  headers and full query strings (`HttpEngineFactory.android.kt`).
- ✅ **Tool-calling architecture (the surprise):** Gemma 4 LiteRT-LM 0.10.2
  expects the **structured** tool-calling channel — `ConversationConfig.tools`
  via `OpenApiTool`, structured `Message.toolCalls` on assistant turns, and
  `Content.ToolResponse` (with a Map/List payload, not a raw JSON string) on
  tool turns. Plain-text tool descriptions or marker-based parsing don't
  unlock the model's tool-use mode. See CLAUDE.md hard invariants 8–11.
- ✅ **107 tests** (commonMain agent + search + storage; UI not unit-tested,
  validated on device).

**Known limitation (not blocking M2):** Brave Search snippets are page
descriptions (typically "Get the latest…"), not extracted answers. The
agent answers correctly when the snippet *contains* the answer (sports
scores, stock prices, recent release dates) but for queries like
"weather in Toronto" the snippet describes weather pages without numbers
in them. Richer snippet handling is M2.1+ scope. *(Closed for the
structured-number verticals: WEATHER renders deterministically from a
national feed (DWML/RSS, §M2.14) and single-instrument FINANCE quotes from
a stockanalysis.com card (§M2.15) — both bypass the snippet→LLM path.)*

Phase A (WS-2 schemas + WS-12 secure-key plumbing) shipped first, then Phase B
(Brave client + cache + post-processor), then Phase C (agent types + prompt
assembler + ReAct loop), then Phase D (UI + settings + nav), then a
multi-round bugfix sequence to get the LiteRT-LM tool-calling integration
right (the architecture had to shift from text-marker parsing to LiteRT-LM's
structured tool API; one `Conversation` per turn rather than per generate).

### M2.1 — Brave `news.results` enhancement ✅ COMPLETE 2026-05-14 — see `docs/BRAVE_SPIKE.md`

PR #12 wires Brave's `news.results[]` block alongside the existing
`web.results[]` parsing. For news-shaped responses (≥3 usable news entries),
`SearchPostProcessor` places up to 2 news hits — ranked by `breaking` then
ISO `page_age` desc — ahead of web hits in the top-5 sources sent to Gemma.
`SearchSource` gained optional `age` and `breaking` fields; the `web_search`
tool description now tells the model to prefer breaking and fresh news on
time-sensitive questions. No new HTTP calls — `news.results` is on every
`/web/search` response we already pay for. Non-news queries (e.g.
`NVDA stock price` returns 0 news hits) render identically to pre-M2.1
output; payload bytes are unchanged.

Partially closes the M2 known limitation: news-shaped queries (breaking
news, election results, war updates) now reach Gemma with explicit
freshness + breaking signals. The residual gap — queries that need a
*structured number* (current weather temp, live stock quote) — is **not**
addressed here; Brave's snippets for those queries still describe the
landing page without the number. The fix is Brave's `/web/rich` callback
flow (vertical hint + second HTTP call), documented in `docs/BRAVE_SPIKE.md`
§4. **Deferred** — two-step request shape, undocumented billing, per-vertical
schemas, empty-payload gotchas. Revisit when telemetry shows a measurable
answer-quality gap on `markets_current` / `weather` / `sports_recent`
queries.

*(Resolution: the `/web/rich` path was prototyped for FINANCE in PR #38
and reverted (the empty-payload gotcha + billing concerns held). The
structured-number gap was instead closed deterministically without a
second Brave call: WEATHER via national DWML/RSS feeds (§M2.14) and
single-instrument FINANCE quotes via a stockanalysis.com page-parse card
(§M2.15). `sports_recent` is served by the Brave `site:` path (PR #34) —
ESPN-style pages carry scores in-snippet, so no structured fetch was
needed.)*

### M2.2 — Persisted multi-conversation history + 8K budget enforcement ✅ COMPLETE 2026-05-15

PR #13 wires the M0-scaffolded `conversations` + `messages` SQLDelight schema
into a live `ConversationRepository`. ChatViewModel now persists each turn,
exposes a "Manage conversations" list under Settings, and supports resuming
a prior thread. Schema bumped v3 → v4 via `3.sqm` (adds
`truncation_acknowledged_at`).

Closes the long-standing PRD §4.2 gap: `TokenBudgetEstimator` (char-based
heuristic, 4 chars/token, 200-token safety margin) checks every send against
a 6,500-token history budget. The first time a conversation would overflow,
a modal dialog asks the user to **Continue** (oldest user/assistant turn-pair
hard-deleted from DB) or **Start new conversation**. Continue persists the
ack so subsequent overflows in that conversation truncate silently. Store
capacity is bounded at 50 conversations (oldest evicted on each `create()`).

Memory rows tagged with a conversation_id survive when that conversation is
deleted — per design, they're user-level facts and outlive their source.
New telemetry counters live in `daily_inference` via the fallthrough route
(no `TelemetryPayloadBuilder` change needed):
`conversations_created_total`, `conversations_resumed_total`,
`conversations_deleted_total:explicit|capacity`,
`conversation_overflow_warned_total`, `conversation_turnpairs_dropped_total`.

Two same-day follow-ups landed alongside PR #13:

- **Empty-bubble fix** (PR #13 commit `94846b8`): `ChatViewModel.toUiMessage()`
  was emitting a UI bubble for every persisted assistant row, including the
  intermediate `Assistant(text="", toolCall=...)` turn that precedes a Tool
  result. Live conversations dodge this because Done only appends the FINAL
  assistant to `ui.messages`; reload paths replayed every row. Filter
  assistant turns with non-null `toolCall` on the rebuild path so loaded
  UIs match live ones.
- **Active-delete clears chat** (PR #13 commit `3402868`): deleting the
  currently-open conversation from Manage Conversations now resets the chat
  surface via a new `ChatViewModel.onConversationDeleted(id)` hook wired
  through `MainScreen`. Avoids the ghost-state where the chat shows messages
  for a row that no longer exists in the DB and the next send would FK-fail.

### M2.3 — Persist assistant citations across resume ✅ COMPLETE 2026-05-15

PR #14 follow-up: `SqlDelightConversationRepository` was dropping
`ChatMessage.Assistant.citations` on write, so resuming a conversation
rebuilt assistant bubbles with empty citation lists — the `(espn.com)` /
`(yahoo.com)` source chips visible during the live turn disappeared on
reload. Citations now serialise into the existing `tool_result_json`
column (free for assistant rows since tool *result* payloads only live on
tool rows) via a small `PersistedCitations` JSON envelope. No schema
migration: pre-PR#13 rows have NULL in that column and decode cleanly to
an empty list.

### M2.4 — On-device TODO list with chat regex commands ✅ COMPLETE 2026-05-15

PR #15 adds a third deterministic chat surface (alongside timers and
alarms): a TODO list reachable from a new header-bar icon to the LEFT of
the timer icon, plus chat commands `add / list / complete / delete / edit
/ clear completed`. Same playbook as the M2-era clock work — a
`TodoIntentDetector` + `TodoCommandParser` short-circuit in `AgentLoop.run`
catch the turn before Gemma sees it; on intent-detected-but-parser-missed
the agent emits a static `TODO_GUIDANCE_TEXT` reply and never falls
through to the LLM. The clock branch runs first so phrases containing both
keywords resolve to clock (preserves the older, more battle-tested path).

Storage is SQLDelight, not SharedPreferences. New `todos` table at schema
v5 via `4.sqm`: priority enum (`LOW`/`MEDIUM`/`HIGH` stored as TEXT for
self-documenting CASE ordering), nullable `due_date_epoch_ms`, completed
flag (0/1), created/updated timestamps, optional notes. `SqlDelightTodoRepository`
owns a `MutableStateFlow<List<Todo>>` so the management screen and the
chat handler share one live source — chat-side mutations propagate
to the UI without cross-coupling, and vice versa. Schema snapshot dance
per invariant #20: `4.db` generated first against v4, then `4.sqm` and
`Todos.sq` added.

Management screen is a full-screen route (`MainRoute.TodoManagement`,
opt for parity with the existing memory-management surface — bottom
sheet would have been faster but the date picker + edit dialog wanted
the room). 614 unit tests, including a load-bearing `AgentLoopTodoTest`
that asserts `engine.generate` is never invoked on a TODO turn (the
no-LLM-fallback contract).

### M2.5 — Proactive memory pressure handling ✅ COMPLETE 2026-05-15

PR #16 closes a Pixel 7 failure mode the M0 memory budget (§2.2) warned
about: another app starts after we've eager-loaded Gemma, system free
RAM drops, our resident ~2.58 GB model goes from "preloaded for snappy
first turn" to "what the OS is desperate to reclaim" — jank, slowdown,
sometimes crashes. Eager warm-up is left intact for the common case;
three new defenses backstop it:

- **`MemoryPressureWatchdog`** (singleton, started from
  `MobileAgentApplication.onCreate`) polls `ActivityManager.getMemoryInfo().availMem`
  every 5 seconds while `SessionState.Loaded`. Below **800 MB free** it
  calls `forceUnload(UnloadReason.LowMemory)` on both
  `InferenceSessionManager` and `AuxModelLifecycleCoordinator`. Mirrors
  the `MainThreadHeartbeatWatchdog` pattern but watches a different
  signal. New `UnloadReason.LowMemory` enum value +
  `INFERENCE_UNLOADED_LOW_MEMORY_TOTAL` telemetry counter keep this
  signal separate from `TrimMemory` / `MainThreadWatchdog` in dashboards.
  Existing deferred-unload contract preserved — mid-generation turns
  finish before the unload lands.

- **`warmUpIfPossible()` memory gate**: refuses the cold load when
  `availMem < EAGER_WARMUP_MIN_FREE_BYTES` (**2.0 GiB**). New
  `WarmUpOutcome.SkippedMemory` outcome + matching counter. Without
  this gate, on a memory-tight start we'd pay a full GPU init + 2.58 GB
  I/O only to have the watchdog yank it back ~1 ms later (observed
  on-device).

- **Send-time gate in `ChatViewModel.send()`** with two thresholds:
  cold-load path (state Unloaded/Failed) requires **2.0 GiB** free,
  hot path (state Loaded/Loading) requires **1.0 GiB**. Hot-path
  matters because without it a send during a transient memory dip
  could start a turn the watchdog yanks mid-stream — the user saw a
  blank assistant bubble with web-search references in the on-device
  repro. On gate hit, a new `MemoryWarning` surfaces an `AlertDialog`
  ("Low memory — close some apps and try again") with Retry / Cancel
  buttons; Retry preserves the pending prompt and re-checks. Dialog
  copy differentiates "load the AI model" vs "safely process this
  request" based on whether the model is already resident.

Thresholds tuned after on-device validation:

| Surface | Threshold |
|---|---|
| Watchdog unload floor | **800 MB** |
| Send-time hot-path gate | **1.0 GiB** |
| Send-time cold-load gate | **2.0 GiB** |
| Eager warm-up gate | **2.0 GiB** |

Initial values (1 GiB / 1.5 / 2.5 / 2.5 GiB) refused sends Pixel 7 could
have handled and churned the model load/unload cycle on routine
background-app spikes. The hot-path gate now sits ~200 MB above the
watchdog floor, so a steady-state turn that starts above 1 GiB has
breathing room before the watchdog catches a transient dip. If a dip
does fire the watchdog mid-turn, the existing deferred-unload contract
lets the turn finish before unload; the next send pays a cold load via
the agent loop's lazy path. Worth revisiting if telemetry shows
mid-turn unloads becoming common or if dialog-fire rates spike.

**Superseded by M2.6**: the four threshold constants above were
consolidated into a JSON-driven `SystemMemoryThresholds` config so all
consumers (watchdog, gates, the new status indicator) read from one
source. The shipped JSON values on main are currently
**300 MB / 500 MB / 800 MB** for on-device experimentation — see M2.6
for the rationale + how to retune via `system_memory_config.json`.

### M2.6 — Header redesign + system memory status indicator ✅ COMPLETE 2026-05-16

PR #18 replaces the chat header's text actions (`Chat` / `Settings` /
`New`) with icons, drops the auto-extraction memory badge (memories
now save only on explicit user request, so the badge has lost its
purpose), and inserts a green / yellow / red **system-RAM status dot**
between the placeholder app icon and the clock cluster. New header
order left → right: app brand · status dot · TODO · Timer · Alarm ·
theme toggle · Settings cog · `+` (new chat). All dots use pinned
Material colors (Green 600 / Amber 700 / Red 600) instead of theme
slots — `colorScheme.primary` adapts to Material You wallpaper (so
"green" can render blue/purple) and `colorScheme.error` desaturates
to pink on dark surfaces, both of which lose the semantic signal a
status dot needs.

The four memory-pressure thresholds inlined across `MemoryPressureWatchdog`,
`ChatViewModel`, and `InferenceSessionManager` (per M2.5) were
consolidated into a single `SystemMemoryThresholds` data class in
`:shared/inference/`, JSON-asset-backed at
`androidApp/src/main/assets/system_memory_config.json`, parsed at
app start through `MemoryModule.provideSystemMemoryThresholds` and
injected into every consumer. The same `thresholds.classify()` helper
drives the status dot's bands so the indicator and the gating logic
**cannot drift out of sync**. JSON values on main:
**300 MB watchdog / 500 MB hot-path / 800 MB cold-load** (also feeds
the eager warm-up gate via `coldLoadMinBytes`). DEFAULT values in code
remain at the conservative 800 MB / 1 GiB / 2 GiB so a parse-failure
fallback doesn't silently regress.

A new `SystemMemoryMonitor` (singleton, started from
`MobileAgentApplication.onCreate`) polls `availMem` every 5 seconds
unconditionally — sibling of `MemoryPressureWatchdog` but no gating
on `SessionState`, since the indicator should reflect device state
regardless of whether the model is resident. Logs once on arm
(thresholds + initial status) and once per band transition with the
avail bytes that triggered it — diagnostic filter
`adb logcat -s SystemMemoryMonitor:I MemoryPressureWatchdog:I`.

Status-dot bands derived from the JSON: **Red `<watchdogUnloadBytes`**,
**Yellow `watchdogUnloadBytes` … `coldLoadMinBytes`**, **Green
`≥coldLoadMinBytes`**. Hot-path threshold (`hotPathMinBytes`) is not
surfaced in the indicator — it stays as a fourth gate inside the
send-time check.

Tests: 8 new `SystemMemoryThresholdsTest` (band boundaries + invariant
rejection), 4 new `SystemMemoryMonitorTest` (StateFlow transitions
under TestDispatcher — a hard-learned subtlety here is the monitor's
`while (isActive) { delay() }` loop has no exit, so calling
`advanceUntilIdle()` infinite-loops the scheduler; tests use
bounded `advanceTimeBy + runCurrent` and `monitor.stop()` inside the
test body before `runTest` exits). 633 unit tests at end of M2.6.

### M2.7 — Explicit remember/forget short-circuit ✅ COMPLETE 2026-05-16

PR #19 closes a bug where `remember my favorite color is blue` produced
**two wrong outcomes**: (a) the post-prefix payload was added as a TODO
(Gemma's training prior associates "remember" with task creation; with
`add_todo` registered as a tool, the model reliably called it), and
(b) the assistant emitted an empty response bubble after the spurious
tool call (the model considered its work done and produced no
follow-up text). The actual memory save was unaffected — `MemoryExtractor`
runs `RememberForgetDetector` downstream and force-creates the memory
regardless of what the LLM did.

Fix mirrors the M2.4 todo + M2-era clock deterministic short-circuit
pattern. `RememberForgetDetector` is now consulted in `AgentLoop.run`
**before** the LLM dispatch, right after the todo block. On a match,
the agent emits a fixed `OK, I'll remember that.` (or
`OK, I'll forget that.`) and returns; `skipMemoryExtraction = false`
on the emitted `Done` is load-bearing so the downstream save still
happens — no save logic duplicated in `AgentLoop`.

Tests: 3 new `AgentLoopMemoryCommandTest` cases lock the contract that
`engine.generate` is **never** invoked on remember/forget turns,
mirroring `AgentLoopTodoTest`'s no-LLM-fallback assertion. 636 unit
tests at end of M2.7.

### M2.8 — Top-5 search results ✅ COMPLETE 2026-05-16

PR #20 raises the per-search payload sent to Gemma from 3 results to 5.
In production the agent frequently ran out of context to synthesize a
good answer with only 3 hits — particularly on multi-faceted factual
queries where the relevant detail sat at result #4 or #5. Bumping
`SearchPostProcessor.TOP_N` 3→5 is paired with `MAX_PAYLOAD_BYTES`
2KB→4KB so 5 full-length snippets fit without the existing progressive
shrinker hitting the 40-char floor on every query. PRD §3.3 amended in
the same PR — the spec is no longer "top three / ≤2KB".

News-shaped queries now fill all 5 slots from `news.results[]` when
available (`MAX_NEWS_IN_TOP_N` 2→5). When news < 5, web tops up the
remaining slots; when news < `NEWS_SHAPED_THRESHOLD` (still 3), the
query falls through to web-only — non-news behaviour is unchanged.

Test deltas: 4 of 11 `SearchPostProcessorTest` cases re-baselined for
the new cap (size assertions, ordering, byte budget). The 4KB byte-cap
guard tests are now the load-bearing safeguard against snippet
truncation — they pass with 5 × ≈200-char snippets landing under 4096
bytes. Cache rows from existing devices (capped at 2KB at insert) still
deserialize cleanly; new rows can grow to 4KB.

### M2.9 — Neutral count-badge styling on header icons ✅ COMPLETE 2026-05-16

PR #21 retints the TODO / Timer / Alarm count badges. Material 3's
`Badge` defaults `containerColor` to `colorScheme.error`, which renders
as a loud red in light theme and pink in dark — visually it read as an
alert rather than a count, and clashed with the otherwise neutral
header chrome.

The first two passes (hardcoded green; then hardcoded black/grey via
`isSystemInDarkTheme()`) were rejected on-device: `isSystemInDarkTheme()`
reads the **system** uiMode, but `MobileAgentTheme` accepts an
explicit `themeMode` override (`Light` / `Dark` / `System`, see
`ui/theme/Theme.kt`) so when the user's theme choice diverges from the
system setting the badge logic and the rendered color scheme drift
apart. Landed solution uses `LocalContentColor.current` for the badge
`containerColor` and `MaterialTheme.colorScheme.surface` for the digit —
the badge now picks up whatever color the surrounding `Icon` is being
tinted with (typically `onSurfaceVariant`), so it reads as part of the
icon and contrast stays correct regardless of themeMode override,
Material You dynamic colors, or future scheme changes.

Single-point edit in `ClockIconButton` (`ChatScreen.kt`); no other
Material `Badge` call-sites exist in the app so the change propagates
to all three icons automatically. Pure UI styling — no test deltas,
no behaviour change.

### M2.10 — Responsive cancel of in-flight generation ✅ COMPLETE 2026-05-16

PR #22 fixes the long-standing complaint that tapping Cancel during
LLM generation often did nothing for several seconds and left the app
visually frozen while tokens kept arriving. Root cause: `Job.cancel()`
in Kotlin only detaches the Flow chain; LiteRT-LM's native decode loop
inside `Conversation.sendMessageAsync` keeps producing tokens on its
own worker thread until end-of-turn, holding GPU/CPU and starving the
UI thread. The Conversation chunks were being silently discarded by
the cancelled flow but the GPU stayed pinned.

Fix has three parts:

1. **Native abort wiring** (`LiteRtInferenceEngine.kt`).
   `com.google.ai.edge.litertlm.Conversation` exposes a `cancelProcess()`
   primitive in 0.10.2 that asks the native side to stop the in-flight
   decode. We now bind it to the consumer coroutine's `Job` via
   `invokeOnCompletion` for both the legacy and structured generation
   paths, so a `cancel()` propagates from Kotlin → JNI → LiteRT-LM
   immediately. The finally block also calls `cancelProcess()` defensively
   before `close()` in case the invokeOnCompletion handler hasn't run
   yet. This is the load-bearing change.
2. **Cooperative cancellation in the tool-call sub-loop**. Added
   `currentCoroutineContext().ensureActive()` at the top of `stepLoop`
   and before each `toolDispatcher.execute()` call so a cancel during
   a slow tool (web search) bails before we round-trip back to Gemma
   for another decode pass. Without this, cancel during the
   "tools-running" portion of a multi-step turn would wait for the
   tool to finish, then Gemma to finish, then the next tool, etc.
3. **Two-stage UI feedback** (`ChatViewModel.kt` + `ChatScreen.kt`).
   Added `ChatUiState.isCancelling`. `ChatViewModel.cancel()` flips
   it synchronously before calling `job.cancel()`, and the catch on
   `CancellationException` clears it. The Cancel button now renders
   "Cancelling…" and disables itself the moment the user taps —
   without this, the still-active "Cancel" label invited repeated
   taps that did nothing and made the app feel broken even during
   the (now-much-shorter) gap between tap and actual abort.

Discovered LiteRT-LM 0.10.2's `cancelProcess()` by inspecting the
api.jar with `javap`; the existing engine code closed the conversation
on flow termination but never asked the native side to abort. The
public LiteRT-LM Android docs don't mention this primitive directly —
this is now captured in CLAUDE.md alongside invariants #1–#11 about
the engine layer.

Test coverage: `ChatViewModelCancelTest` exercises the synchronous
isCancelling flip, idempotent re-taps, and the no-op when nothing is
generating. The native cancelProcess() round-trip can only be
verified on-device because LiteRT-LM types aren't mockable from common
test fixtures — manual verification ran on Pixel 7 against several
streaming prompts that previously hung for 3-5 s after Cancel was
tapped. 639 unit tests at end of M2.10 (+3 over M2.7; M2.8 and M2.9
had no test deltas).

### M2.11 — Vertical search routing + onboarding-driven preferences (PR #23, in review)

Layered on top of M4's binary pre-flight classifier without retraining it.
Adds:

- **Subtype detector** (`SearchSubtypeDetector`) — regex/keyword refinement
  that picks `WEATHER | SPORTS | FINANCE | NEWS | GENERAL` from the user's
  literal query once the classifier decides search is needed. Order:
  weather → sports → finance → news → general fallback. The dataset
  category labels validated in `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md`
  (`sports_recent`, `markets_current`, `weather`, `news_current`) form the
  ground truth a v1.x classifier retrain could promote into a fourth head.
- **Hybrid fetch** — News reuses Brave with a `site:` filter built from
  user-preferred domains (`BraveSiteFilterAdapter`); Weather / Sports /
  Finance directly fetch RSS / JSON / HTML (`FeedAdapter`) with a minimal
  hand-rolled Readability extractor (`HtmlReadabilityExtractor`) and an
  RSS 2.0 / Atom parser (`RssParser`). General search is unchanged. All
  vertical fetches run on `Dispatchers.IO` (invariant #1) and present a
  browser-shaped UA to avoid feed providers' default-Ktor-UA blocks.
  *(Superseded — PR #34: SPORTS moved off `FeedAdapter` onto the Brave
  `site:` path (`BraveSiteFilterAdapter`), since RSS headlines can't answer
  historical queries like "who won the masters last year". PR #35: FINANCE
  followed the same move, and the dedicated STOCKS subtype (PR #27's
  two-call `StockLookupAdapter` ticker resolver) was deleted — a single
  Brave query over finance domains answers both market news and
  single-instrument quotes ("nvidia stock price"). `FeedAdapter` now serves
  Weather only. See CLAUDE.md invariants #30–#31.)*
- **Per-vertical preferences** persisted via DataStore Preferences
  (`SearchPreferencesRepository`). Country-keyed defaults live in
  `androidApp/src/main/assets/search_defaults.json` (invariant #14) and
  are seeded on location capture. Editable from Settings → Search sources.
- **Onboarding step 4** (`LocationPickerScreen`) captures country / region
  / city with device-locale prefill. New `OnboardingStep.Location` keys
  off a new `OnboardingPreferences.locationDecided` boolean.
  *(Superseded — PR #37: onboarding now captures **country only**; the
  weather path resolves the specific city + state/province at query time.
  See §M2.14.)*
- **Telemetry**: the existing `PREFLIGHT_HIGH_BAND_TOTAL` counter is tagged
  with the chosen subtype (`weather` / `sports` / `finance` / `news` /
  `general`) — no new counter names.

Classifier `.tflite` and `preflight_config.json` are untouched. The
`AgentLoop` FireSearch path keeps the legacy direct-`SearchService.search`
fallback when the dispatcher isn't wired so older tests stay green.

Test coverage: `SearchSubtypeDetectorTest` (8 tests, table-driven across
all 5 subtypes + ambiguity), `HtmlReadabilityExtractorTest` (5),
`RssParserTest` (4), `DefaultSiteResolverTest` (6). Existing
`PreflightRouterTest` continues to pass — subtype defaults to GENERAL so
the M4 assertions are byte-identical. **662 unit tests** at end of M2.11
(+23 over M2.10). Adapter MockEngine tests + AgentLoop vertical-routing
integration test + Compose-UI instrumentation are scoped to a follow-up
PR; this one ships the architectural seam.

### M2.12 — SPORTS vertical → Brave `site:` path ✅ COMPLETE 2026-05-18

PR #34 moves SPORTS off `FeedAdapter` (RSS) onto `BraveSiteFilterAdapter`
— the same Brave `site:`-filtered web search NEWS uses. RSS feeds only
carry recent headlines and can't answer historical/specific queries ("who
won the masters last year"); a search restricted to `site:espn.com` can.
Sports sources in `search_defaults.json` and the Settings Add-source dialog
become `BRAVE_SITE_FILTER`; the dispatcher runs exactly one adapter per
subtype, so sports queries no longer touch RSS. See CLAUDE.md invariant #31.

### M2.13 — FINANCE → Brave `site:` + STOCKS merged + single source/citation ✅ COMPLETE 2026-05-18

PR #35 applies the same RSS→Brave move to FINANCE and **deletes the
dedicated STOCKS subtype** (PR #27's two-call `StockLookupAdapter` ticker
resolver against stockanalysis.com): one Brave query like `nvidia stock
price (site:bloomberg.com)` answers both market news and single-instrument
quotes, so the ticker round-trip is gone. `SearchSubtypeDetector` loses
`STOCKS_PATTERN` (single-instrument vocab folds into `FINANCE_PATTERN`);
no STOCKS `when` branches remain. `FeedAdapter` is now WEATHER-only.

PR #36 follow-ups: SPORTS & FINANCE become **single-source /
single-citation-chip** — `search_defaults.json` lists exactly one domain
per country and the dispatcher wires them `maxDomains = 1, maxCitations =
1`. The citation cap trims only `payload.sources` (UI chips) via
`SearchPostProcessor.limitCitations`; `payload.json` (the model's
`[SEARCH CONTEXT]`) keeps Brave's full top-N so a stock lookup still sees
the data. Single-domain rewrites emit a bare `query site:domain` (no
parens). NEWS leaves `maxCitations` null. See CLAUDE.md invariants #30–#31.

### M2.14 — US weather via DWML + query-time location (country-only onboarding) ✅ COMPLETE 2026-05-21

PR #37. **WEATHER renders deterministically and is never consulted to the
LLM** (Gemma mangles numbers, can't read a feed, doesn't know location).

- **US weather via `forecast.weather.gov` DWML** — new `SourceKind.DWML` +
  regex-based `DwmlParser` (mirrors `RssParser`, emits the same `RssEntry`
  shape); `FeedAdapter` gains a DWML branch. US weather becomes a SINGLE
  DWML source (weather.com removed), matching how Environment Canada RSS
  serves Canada. `WeatherResponseFormatter` renders °F / label-less text
  cleanly with no change.
- **Country-only onboarding + query-time location.** `LocationPickerScreen`
  → single country dropdown; `OnboardingViewModel.saveLocation(country)`
  seeds default sources and leaves `regionCode`/`city` empty. The new
  `WeatherLocationResolver` resolves the city per query: (a) city +
  state/province parsed from the query against the bundled `LocationCatalog`
  (disambiguated by state/province → code → onboarded country →
  specificity); else (b) a saved location memory; else (c) a deterministic
  prompt. The resolved city's country selects the national source via
  `DefaultSiteResolver`, so "weather in Toronto" works for a US-onboarded
  user.
- **Force-fire outside the classifier.** The shipped classifier under-fires
  on bare forecast queries, so AgentLoop force-fires WEATHER when the query
  names a resolvable catalog city OR matches the tight whole-message
  `BARE_WEATHER_PATTERN`. The saved-location fallback (b) is reachable ONLY
  via the bare pattern, so "what's the weather typically like in England"
  defers to the classifier instead of being answered from the saved city.
- **Consent-card location memory.** When the city came from the user's own
  words, `AgentLoop` emits `AgentEvent.Done.locationToRemember` and
  `ChatViewModel` surfaces a Save/Dismiss card via
  `MemoryExtractor.proposeLocationMemory` (consent path, not force-save;
  deduped — no re-prompt for a known place).
- **`locations.json`** expanded to all CA provinces/territories + 50 US
  states + DC with GPS coords (new cities work automatically —
  coordinate-driven endpoints).
- **Brave query logging** at the client chokepoint: `KtorBraveSearchClient`
  logs the outgoing `q` via `Log.i("BraveApi", …)` (diagnostic logcat only,
  NOT telemetry). See CLAUDE.md invariant #32 (and #28 for the tag).

New tests: `DwmlParserTest`, `WeatherLocationResolverTest`,
`AgentLoopPreflightTest` weather cases, `MemoryExtractorTest`,
`CitationUrlRewriteTest` + `WeatherResponseFormatterTest` DWML cases.

### M2.15 — FINANCE deterministic stock-quote card ✅ COMPLETE 2026-05-22

PR #38. Single-instrument FINANCE quotes ("what is the stock price of
Nvidia") render as a **deterministic card via stockanalysis.com, bypassing
the LLM**. `VerticalSearchDispatcherFactory` wires FINANCE to
`FinanceQuoteAdapter(fallback = BraveSiteFilterAdapter)`. Flow: (1) a Brave
`site:finance.yahoo.com` search resolves the ticker from the result URL
(`/quote/<TICKER>/`); (2) GET `stockanalysis.com/stocks/<ticker>/` and
`StockAnalysisParser` extracts the live quote from the page's minified
`quote:{p,c,cp,h,l,v,h52,l52,…}` blob + `marketCap`/`peRatio`; (3)
`StockResponseFormatter` renders a `"subtype":"stock_quote"` card. On any
miss (no ticker, fetch/parse failure) the adapter returns the Brave snippet
outcome unchanged → LLM fallback (pre-#38 site-filter behavior). NOT cached
(a cached price is a stale price). `AgentLoop` has a FINANCE direct-render
block after the WEATHER block.

This partially re-introduces the stockanalysis.com dependency §M2.13 (PR
#35) removed — but as a deterministic page-parse + card, not the old
`StockLookupAdapter` feeding page text to Gemma. An earlier #38 iteration
used Brave's `/web/rich` callback (closing the §M2.1 deferral) but was
reverted for the stockanalysis.com parse. New tests:
`StockAnalysisParserTest`, `StockResponseFormatterTest`, AgentLoop finance
cases. See CLAUDE.md invariant #33.

### M2.16 — Search context on the current user turn (recency fix) ✅ COMPLETE 2026-05-22

PR #39. Fixes the disable-then-enable-search bug: with search off, a recency
query gets the correct "I don't have real-time data" refusal; the user enables
search and re-asks; search fires and results inject, but Gemma **repeats the
refusal**. Cause is positional — the system instruction sits at the far front
of the context window while the prior refusal is the assistant turn right
before the current query (next to the generation point), so a 2B model anchors
on its own recent refusal and ignores distant evidence; the `PREFLIGHT_NOTICE`
forbidding the refusal lost the same recency battle.

Fix: the `[SEARCH CONTEXT]` block + notice now ride on the **current user
message** (sent via `sendMessageAsync`) instead of the system instruction —
canonical RAG placement, so fresh evidence is the most-recent thing read.
`PromptAssembler.appendSearchContext` appends to the tail `USER`
`HistoryMessage` (defensive no-op if the tail isn't a user turn);
`buildSystemInstruction` no longer takes `searchContext`. The engine layer is
unchanged. Token-neutral. History-hygiene (pruning the stale refusal) was
rejected — `ChatMessage` has no "used search" flag, so detection would need
brittle phrase/locale matching. New regression test in `PromptAssemblerTest`;
`AgentLoopPreflightTest`/`AgentLoopMemoryTest` re-pointed to assert the block
on `request.history.last().text`; `CanonicalEvalTest` widens its block-detection
haystack. See SYSTEM_PROMPT.md §6.3 and CLAUDE.md invariant #34.

### M2.17 — SPORTS via Brave LLM Context API + search-turn decode hardening ✅ COMPLETE 2026-05-22

PR #41. Moves SPORTS off `/web/search` onto Brave's **LLM Context** endpoint
(`/res/v1/llm/context`), which returns pre-extracted page content (prose,
tables) instead of ≤200-char index snippets — same Brave Search plan + token,
single round-trip. A second `SearchService` (`@SportsSearch`) wraps a new
`KtorBraveLlmContextClient` (same `BraveSearchClient` interface, so key/cache/
counters are reused); `LlmContextPostProcessor` parses `grounding.generic[]`
into the existing `FormattedSearchPayload`. The shared `SearchCacheDao` is
namespaced (`cacheNamespace = "sports:"`) so a SPORTS query can't collide with
an identical GENERAL query's `/web/search` payload.

Shipped iteratively against on-device logcat while chasing a digit-corruption
symptom (Gemma E2B writing "114" as "1114" when copying scores):
- **History scoping** — search-grounded turns (a `[SEARCH CONTEXT]` block is
  present) drop prior conversation history from the prompt; full history is
  still persisted for follow-ups. Subsumes the M2.16 stale-refusal recency fix.
- **Greedy decoding** — those turns set `SamplingParams.GREEDY` (`topK = 1`
  argmax; temperature held at `1.0`, not `0.0`, to avoid a native-sampler
  divide-by-zero / "temp-disabled" footgun). Per-request override threaded
  through `GenerationRequest.sampling`.
- **Single clean source** — SPORTS re-pinned to `site:` (one domain: espn.com
  US / tsn.ca CA) after an initial *unpinned* attempt ranked nba.com video
  clips + a schedule table above the scores; `MAX_URLS = 1`; JSON-blob snippet
  chunks (VideoObject / standings tables) stripped to prose.
- **Observability** — raw Brave response dumped to logcat (`BraveApi`), and the
  active sampling printed on the `AgentLoop` "sending to engine" line.

These reduce but do not eliminate digit errors — the residual is the 2B model's
transcription floor on a number-dense context; the only guaranteed fix is a
deterministic score card (regex-parse + render, bypass the LLM, like
WEATHER/FINANCE), deferred as high-effort/brittle for unstructured sports prose.
See CLAUDE.md invariants #35 and #36.

### M2.18 — GENERAL/NEWS/SPORTS on LLM Context (FINANCE stays /web/search) ✅ COMPLETE 2026-05-22

PR #42. Generalizes PR #41 (M2.17) to the verticals whose results the LLM
*reads*: **GENERAL** and **NEWS** move off `/web/search` onto
`KtorBraveLlmContextClient` (`/llm/context`) for pre-extracted page content.
**FINANCE deliberately stays on `/web/search`** — its `FinanceQuoteAdapter`
resolves a ticker by parsing the `finance.yahoo.com/quote/<TICKER>/` URL, which
`/llm/context` doesn't return (it returns `/markets/stocks/articles/…`).
WEATHER and the single-instrument FINANCE quote card never touch Brave.

Because PR #41 already made the adapter, post-processor, greedy decoding, and
history scoping endpoint-agnostic, this is DI wiring + one parameterization.
Four coexisting `BraveSearchClient`s, each its own `SearchService` + cache
namespace:
- **GENERAL** — default `KtorBraveLlmContextClient(maxUrls = 3)`, `"ctx:"`.
  `maxUrls`/`maxTokens`/`maxSnippetsPerUrl` became ctor params (defaults
  preserve SPORTS).
- **NEWS** — `@NewsSearch`, `KtorBraveLlmContextClient(maxUrls = 10)`, `"news:"`;
  adapter `maxCitations = 10` for up to 10 source chips. Two US defaults
  (`apnews.com` + `reuters.com`).
- **SPORTS** — `@SportsSearch`, `KtorBraveLlmContextClient(maxUrls = 1)`,
  `"sports:"` (single clean source).
- **FINANCE** — `@FinanceSearch`, `KtorBraveSearchClient` (`/web/search`),
  `"fin:"`. Keeps the deterministic stockanalysis.com card working.

The `"ctx:"` namespace also invalidates pre-#42 `/web/search`-shaped rows
(payload type identical, so it's invalidation not a break).
`KtorBraveSearchClient` + `SearchPostProcessor.format` stay live (FINANCE);
`limitCitations` is endpoint-agnostic. `VerticalSearchDispatcherFactory.create`
+ `VerticalSearchModule` thread the three qualified services.

Tests: new `KtorBraveLlmContextClientTest` (MockEngine — asserts injected
`maxUrls`/budget params + path + auth header reach the wire), extended
`SearchServiceTest` (`"ctx:"` bypasses legacy un-namespaced rows).

**Caught on-device:** the first iteration moved FINANCE onto `/llm/context` too,
which broke ticker resolution (logcat `no ticker in Brave results — snippet
fallback`); reverted by giving FINANCE its own `/web/search` service.

Post-review on-device tuning (all in PR #42): NEWS gets its own `maxUrls = 10` /
`maxCitations = 10` service with two US defaults (apnews.com + reuters.com); the
preflight `high_band` threshold dropped 0.4 → 0.3 (a "latest news headlines"
query scored 0.391, just under the old band). See CLAUDE.md invariants #14, #37.

### M3 — Datasets & classifier training ✅ COMPLETE 2026-05-09 — see `docs/M3_PLAN.md`

Detailed phase-by-phase plan, ratified decisions, and exit criteria live in
`docs/M3_PLAN.md`. Top-line summary:

- **WS-5:** Pre-flight dataset shipped at `preflight_v1.0.0` (11,670 examples,
  580 hand-authored adversarial pair examples covering 80 prototype pairs across
  the 5 §2.4 pair types). Distribution within ±7pp of every §2.2 proportion;
  naturalistic share 28.1% (close to the 30% target). Manifest + frozen
  regression-split SHA-256 in `datasets/preflight/MANIFEST.md`.
- **WS-6:** Memory dataset shipped at `memory_v1.0.0` (7,707 examples).
  Forget=527 + Remember=737 (both far over the §3.5 ≥200 minimums).
  Hard-case pairs across implicit_vs_explicit_preference / temporary_vs_stable /
  sensitive shipped via 48 hand-authored prototypes. Manifest in
  `datasets/memory/MANIFEST.md`.
- **WS-7:** Single shared DistilBERT-base encoder + 3 task heads. INT8
  weight-only LiteRT artifact at `models/preflight_memory_shared_v1.0.0_int8.tflite`
  (67.7 MB). Multi-task fine-tune (5 epochs CE, lr 2e-5, batch 32) — see model card.

| §7 metric | Target | v1 | Status |
|---|---|---|---|
| Pre-flight high-band precision | ≥95% | 88.6% (FP32) / 92.4% (INT8) | within 7pp |
| Pre-flight time-sensitive recall (per-class argmax) | ≥90% | 86.8% | within 4pp |
| Memory presence precision | ≥90% | **92.2%** | ✓ |
| Memory presence (regression split) | ≥90% | **96.2%** | ✓ |
| Forward-pass latency p95 (host proxy / Pixel 7 estimate) | <80 ms | 14.7 ms / ~45-60 ms | ✓ |
| Adversarial-pair accuracy | — | 83.7% test / 88.0% regression | informational |

**§7 GATE: FAIL by 4-7pp on two pre-flight metrics — defensible v1 with
documented v1.x improvement path** (telemetry-driven dataset expansion at
the search_required ↔ ambiguous boundary). The model card at
`docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` enumerates known
weaknesses + improvement queue. Threshold-sweep analysis in the eval
report shows the FP32 model can't reach 95% precision at any threshold in
[0.5, 0.95] (caps at 0.905) — the gap is dataset-level boundary noise,
not a calibration problem solvable by hyperparameter tuning.

**Phase H handoff to M4 (WS-8) at `docs/M3_M4_HANDOFF.md`**: tflite
signature, threshold defaults, tokenizer requirements, regression CI gate
hashes, v1.x improvement queue.

### M4 — Pre-flight classifier integration ✅ COMPLETE 2026-05-10 — see `docs/M4_PLAN.md`

Detailed phase-by-phase status, ratified decisions, and exit criteria live
in `docs/M4_PLAN.md`. Top-line summary:

- **WS-8 shipped:**
  - Classifier engine (`LiteRtClassifierEngine`) in `:shared/androidMain` on
    `com.google.ai.edge.litert:litert:2.1.4` (the runtime-version match for
    ai-edge-quantizer's export tooling — Play Services TFLite produced
    numerically broken outputs, see CLAUDE.md inv. #18).
  - Custom WordPiece tokenizer in `:shared/commonMain`, byte-exact against
    HuggingFace `distilbert-base-uncased` over 22 canonical fixtures.
  - Three-band router with deterministic-only rewriter (no Gemma fallback;
    memory-context queries abort to FallThrough — M5 promotes them).
  - `AgentLoop` integration: high-band → run search inline + inject as
    synthetic Tool message + flip `preflightNotice = true`. Pre-flight
    tool calls don't count toward Gemma's 3-call cap.
  - JSON config shipped at `:androidApp/src/main/assets/preflight_config.json`
    (thresholds 0.85 / 0.15, model_version pinned).
  - Asset bundling Gradle task with SHA-256 verification on the .tflite.
- **WS-14 shipped:** `ct-regression-check` CLI in `classifier-training/`.
  Verifies regression-JSONL SHA-256 against MANIFEST.md, runs
  `ct-eval-classifier --split regression`, diffs 19 gate metrics against
  the v1.0 baseline, exits non-zero on any >2pp regression. Three exit
  paths verified end-to-end. Hosted-CI runner deferred to M6.

| Metric | Result |
|---|---|
| Pixel 7 latency p95 (CPU XNNPACK) | 113 ms (M4 gate <150 ms ✓; PRD §2.3 80 ms aspiration tracked as model card v1.x #5) |
| End-to-end smoke on Pixel 7 | ✓ all 3 canonical queries route correctly (FireSearch / SkipSearch / SkipSearch) |
| Tokenizer fixture match (vs HF Python) | ✓ byte-exact across 22 canonical strings |
| Unit tests | 142/142 Kotlin + 40/40 Python |
| Regression gate smoke (re-eval v1.0 ckpt) | ✓ PASS, byte-identical baseline reproduction |

**M5 hand-off at `docs/M4_M5_HANDOFF.md`:** classifier engine reuse pattern
(M5 calls `engine.classify(userTurn + assistantResponse)` and reads
`presenceLogits` + `categoryLogits` — same forward pass, no second model
load), pair-encoder API on `WordPieceTokenizer`, expected probability
shapes.

### M5 — Memory subsystem ✅ COMPLETE 2026-05-10 — see `docs/M5_PLAN.md`

Detailed phase-by-phase status, ratified decisions, and exit criteria live
in `docs/M5_PLAN.md`. Top-line summary:

- **WS-9 shipped:**
  - `all-MiniLM-L6-v2` INT8 embedder (23.5 MB, exported via
    `classifier-training/scripts/export_minilm_litert.py`) running on
    `com.google.ai.edge.litert:litert:2.1.4` (CPU XNNPACK; GPU rejected
    the graph for the same reason as the classifier).
  - Memory store: SQLite table per PRD §3.2.4 with embeddings as BLOBs
    (little-endian Float32, 1,536 bytes per row); brute-force cosine
    over non-expired rows.
  - Retrieval (K=5 / threshold 0.5) with atomic `last_accessed` +
    `access_count` bump on hits; eviction cascade (expired → 90-day
    stale → LRU+freq) runs pre-insert at the 1,000-row capacity.
  - `[MEMORY CONTEXT BLOCK]` (SYSTEM_PROMPT.md §5) injected when
    retrieval finds anything; bullet format `- (<category>) <text>`.
  - Possessive substitution in `QueryRewriter` — "did my team win" with
    seeded Eagles preference rewrites to "did philadelphia eagles win
    <date>" and routes high-band FireSearch.
- **WS-10 shipped:**
  - `MemoryExtractor` runs on `Dispatchers.IO` after each
    `AgentEvent.Done`. Detector path (Remember/Forget) bypasses
    classifier; classifier path runs `encodePair` → presence head argMax →
    multi-label sigmoid > 0.5 per category.
  - Verbatim-user-text memories per Q3 in M5_PLAN.md §2 (Gemma-rewritten
    canonical text deferred to v1.x).
  - Dedup via cosine > 0.85; forget at the *retrieval* threshold (0.5)
    so loosely-named queries resolve.
  - Remember/forget regex covers `that|this|me|i'm|i am|i|to|about|my|
    our|the|when|where|how|...` per the on-device review fix.
- **WS-11 shipped:**
  - `MemoryScreen` (grouped by category, per-row delete with confirmation,
    clear-all, creation toggle), `ConversationMemoryListScreen`
    (per-chat list with category chip), `ConversationMemoryBadge`
    (chat top bar; hidden when count == 0), Settings entry.
  - Bonus fix: `imePadding()` on chat + settings columns so the soft
    keyboard no longer hides the input.
- **WS-12 shipped:**
  - Memory subsystem audit: every `Log.*` / `logger` call emits counts,
    IDs, accelerator names, or `text.length` only — never raw memory
    text or user payloads. Documented in `docs/M5_PLAN.md` §6 / §7.
  - Telemetry-exclusion comment markers in `Memories.sq` and
    `MemoryExtractor` so the M6 WS-13 telemetry builder cannot
    accidentally read memory content.
  - DB file lives at `Context.dataDir/databases/mobile_agent.db` —
    Android FBE Credential Encrypted Storage by default on Android 16
    (PRD §4.4 satisfied without extra config).

| Metric | Result |
|---|---|
| Embedder forward pass p95 (Pixel 7 CPU) | 40.68 ms |
| Cosine over 1k entries p95 | 31.87 ms (BLOB JNI dominates; v1.x can pre-load to memory) |
| **End-to-end retrieval p95 (PRD §3.2.4 budget 100 ms)** | **72.01 ms ✓** |
| Auxiliary footprint (classifier 67.7 + embedder 23.5) | 91.2 MB / 200 MB cap ✓ |
| Unit tests | 265/265 (was 142 at end of M4 — +123 in M5) |

**M6 hand-off at `docs/M5_M6_HANDOFF.md`** covers the telemetry
counter-only contract, schema-migration follow-up (the `access_count`
column was added in-place; existing dev installs need `pm clear`
before M6 tightens the migration story), classifier recall improvement
queue (RELATIONSHIP under-represented in v1.0), and embedder
GPU-re-export option.

### M6 — Polish, eval, telemetry ✅ COMPLETE 2026-05-11 — see `docs/M6_PLAN.md` + `docs/M6_M7_HANDOFF.md`

- **WS-11 (UX polish + a11y):** 3-screen first-run onboarding (disclosure → Brave key → telemetry consent); `ThermalBanner` at MODERATE/SEVERE (dismissible) + CRITICAL (full block, Send disabled); accessibility audit — `liveRegion = Polite` on streaming bubbles, onboarding heading semantics; chat empty state polished. Live TalkBack walk + dynamic-type 200% deferred to M7 closed-beta bug-bash.
- **WS-13 (telemetry):** Opt-in (default OFF) Firebase Analytics pipeline. 4 themed daily events (`daily_inference`, `daily_preflight`, `daily_search`, `daily_memory`) routed by counter prefix. `TelemetryPayloadBuilder` reads only aggregate tables — memory-exclusion canary test enforces no content leaks. `TelemetryUploadWorker` (24h, UNMETERED). Settings toggle + first-run consent screen + DEBUG "Run telemetry upload now" button. Schema v2 → v3 via `2.sqm`.
- **WS-14 (hosted CI):** `.github/workflows/regression-gate.yml` runs `ct-regression-check --skip-eval` on PRs touching `models/`/`datasets/`/`classifier-training/`; `workflow_dispatch` for full eval. `.github/workflows/prompt-eval-gate.yml` runs `CanonicalEvalTest` (15 canonical queries with fake classifier outputs) on PRs touching `SYSTEM_PROMPT.md` or routing-layer code.
- **WS-15 (Crashlytics + perf):** Firebase Crashlytics behind `SafeCrashReporter` facade with shared `ContentRedactor` (Authorization / X-Subscription-Token / Bearer / URL queries). `RedactedThrowable` preserves stack trace + class name. Single consent toggle gates Analytics + Crashlytics. NDK Crashlytics + detekt lint rule deferred to v1.x. Privacy policy + Data Safety notes drafted at `docs/`.
- **M5 carry-overs landed:** schema migration `1.sqm` (v1→v2, `access_count` + 2 indexes) + `verifyMigrations=true` build-time gate; telemetry counter API + `:shared/commonMain/telemetry/CounterNames` per M5_M6_HANDOFF §1; hosted CI for `ct-regression-check` (WS-14 above).
- **Eager Gemma load (new in M6):** `InferenceSessionManager.warmUpIfPossible()` + `LifecycleResumeEffect(route)` in MainScreen fires warm-up on chat-screen entry (300ms debounce). Thermal-gated at SEVERE+. **First-token target calibrated to 1–5s acceptable, <5s required** (originally aspired <1.5s; real Pixel 7 measurement is 1–3s — see `docs/M6_M7_HANDOFF.md` §5 Finding 2). Existing 5-min idle unload + `onTrimMemory()` paths preserved; new `UnloadReason` enum distinguishes idle / TrimMemory / Manual for telemetry attribution.
- **Eager Gemma load reverted in PR #25 (2026-05-19):** Regex tools (clock/todo/memory) and classifier-routed verticals (weather) don't need the LLM at all, so the eager warm-up wasted a 4–8 s cold-load + 2.58 GB I/O on every chat-screen entry. `InferenceSessionManager.warmUpIfPossible`, the `INFERENCE_WARMUP_*` counters, `idleTimeoutAfterWarmUp`, and the warm-up memory gate were all deleted. Aux engines (classifier + embedder) still warm on RESUME — they run on every non-regex turn and cost ~150 ms total. Gemma now loads on the first `generate()` call; fall-through queries pay the cold load on first send. The 1–5 s first-token target is unchanged for *steady-state* turns within the 5-min idle window. **664 unit tests** after PR #25 (~14 warm-up tests deleted; net +2 over M2.11's 662 reflecting tests added in PRs #18–#23).
- **Deliverable:** Internal-quality build ready for closed beta. **318 unit tests** at end of M6 (+53 over M5). All Phase A–F host-side green; Phase G on-device walkthrough on Pixel 7 confirmed onboarding, eager load, thermal banner/block, 4-event telemetry pipeline + consent-OFF gate, bug-bash drills 7+9. Known v1.0 limitation surfaced (Phase G Finding 1): memory-rewrite chain misses on verbatim-text + middle-band classifier (`my favorite team is the eagles` stored verbatim doesn't clear retrieval cosine for "did my team win last night"); v1.x fix is Gemma-generated canonical memory text per M5_M6_HANDOFF §6.

### M7 — Closed beta → public launch (weeks 22–26)

- Play Console internal testing track → closed testing (~50–200 users) → open testing (1k+ users) → production.
- Bug bash on Pixel 7 across Android 16 stable + any next-version preview.
- Address Play Store review feedback (Data Safety, content rating, on-device LLM disclosure).
- Public Play Store launch.

---

## 6. Pixel 7 + Android 16 specifics

- **Tensor G2 acceleration:** LiteRT-LM delegate support for the G2 EdgeTPU is unconfirmed and is the highest-impact unknown in M0. Fallback path is GPU delegate (Mali-G710 via OpenCL/Vulkan); CPU-only would likely miss even relaxed targets and would force a model swap to E2B.
- **Foreground service type:** Inference must run as a foreground service during active generation. Android 14+ requires a typed `foregroundServiceType`; `specialUse` with a stated reason is the most defensible. Validate against Android 16's stricter rules during M0.
- **16 KB native page sizes:** Android 15+ devices expect 16 KB-aligned native libraries. Verify the LiteRT-LM AAR ships 16 KB-aligned `.so` files; if not, file upstream and use a temporary 4 KB-emulated build.
- **Edge-to-edge enforcement:** Compose UI must opt into edge-to-edge from day one (Android 15+ enforces it for apps targeting recent SDKs).
- **Predictive back gesture:** Compose Navigation must support predictive back (default in modern Compose; verify chat input doesn't capture back inappropriately).
- **Battery optimization:** Model download via WorkManager respects Doze; inference-time foreground service is exempt while running but should not linger after generation completes.
- **Thermal API:** `PowerManager.OnThermalStatusChangedListener` for monitoring; throttle generation rate when status reaches `THERMAL_STATUS_SEVERE`, refuse to start generation in `THERMAL_STATUS_CRITICAL`.
- **Memory pressure:** Register `ComponentCallbacks2.onTrimMemory()` to proactively unload Gemma 4 when the system signals `TRIM_MEMORY_RUNNING_CRITICAL` or higher, even before the 5-minute idle timer.
- **Android 16 GA timing:** If Android 16 is not yet GA on Pixel 7 at launch, the addressable user base is limited to developer-preview/beta-channel users. **This is an open question that needs answering before final launch date is set** — see §8.

---

## 7. Risks & mitigations

| Risk | Severity | Status / Mitigation |
|---|---|---|
| ~~Gemma 4 E4B doesn't fit perf/memory envelope on Pixel 7~~ | ~~High~~ | ✅ RESOLVED 2026-05-04: switched to E2B (`docs/M0_DECISION_MEMO.md` §3 Decision 1). Peak PSS 3.52 GB under 4 GB cap. |
| ~~Tensor G2 NPU delegate not available in LiteRT-LM~~ | ~~High~~ | ✅ RESOLVED 2026-05-05: NPU not exposed; GPU (Mali-G710 via Play Services TFLite OpenCL) works at 13.5 tok/s mean. |
| ~~Classifier .tflite runtime mismatch on Android~~ | ~~High~~ | ✅ RESOLVED 2026-05-10 (M4 Phase B): Play Services TFLite produces silently-broken outputs for our ai-edge-quantizer INT8 model (logits ~1500x magnitude vs Python reference). Switched to `com.google.ai.edge.litert:litert:2.1.4` (Android port of ai-edge-litert), the runtime that matches the export tooling. Documented as CLAUDE.md inv. #18. GPU delegate still refuses the graph (`BROADCAST_TO`/`EMBEDDING_LOOKUP` unsupported) — runs on CPU XNNPACK at p95=113 ms, accepting a 33 ms gap from the 80 ms PRD §2.3 aspiration in exchange for net-positive user-facing latency (saves 2-3 s Gemma round-trip on high-band queries). |
| LiteRT-LM API churn breaks integration mid-Phase 1 | Medium | Pin to specific release (currently 0.10.2). Isolate behind `InferenceEngine` interface. Keep MediaPipe LLM Inference API as a documented escape hatch. |
| Classifier accuracy misses PRD §7 targets after training | High | Dataset construction is M3 critical path (`docs/M3_PLAN.md`). Adversarial pair set is a quality gate. Iterate on dataset if first training pass misses. Hold integration (M4) until thresholds met. |
| Dataset construction blocks classifier integration | High | Solo synthetic-first via local Ollama (zero marginal cost). Phased plan in `docs/M3_PLAN.md` budgets 10 weeks. |
| Pixel 7 thermal throttling under sustained inference | Medium | Thermal monitoring + token-rate throttling in WS-1. UI warnings per PRD §4.3. M0 spike hit MODERATE (2) max under 109 s of generation, never SEVERE. |
| Play Store privacy/data-safety review of an on-device LLM | Medium | Data Safety form clearly states only Brave queries leave device. Privacy policy explicit about on-device processing. Telemetry is opt-in and explicitly excludes content. Engage Play Console review early in M7. |
| Brave API costs scale beyond free tier in dev/test | Low | Aggressive caching is already specified. Internal builds rate-limit to development quotas. Production is BYOK so per-user costs are user-borne. |
| Gemma 4 model artifact availability/licensing changes | Medium | Confirm distribution rights and CDN hosting plan in M0. Have a checksum-pinned download URL under our control, not Google's. |
| 8 GB RAM headroom too tight when other apps are running | Medium | ✅ MITIGATED in M1 WS-1 Phase A: `onTrimMemory()` proactively unloads Gemma under system pressure (`MobileAgentApplication.onTrimMemory` → `InferenceSessionManager.forceUnload`). 5-min idle unload as baseline. |
| Play Services TFLite is a runtime dep for GPU | Medium | New risk surfaced in M0. Devices without recent Play Services (CN AOSP forks, GrapheneOS) will fall back to CPU; engine already degrades via `LiteRtInferenceEngine.tryInitialize` and surfaces it via `ModelHandle.activeAccelerator`. Add to M5/M6 compatibility matrix. |

---

## 8. Open questions

Status as of 2026-05-08:

1. **Android 16 GA on Pixel 7:** OPEN. What is the production rollout date? If not GA at our launch, what is the addressable Pixel 7 + Android 16 user base?
2. ~~**LiteRT-LM Tensor G2 delegate:**~~ ✅ RESOLVED 2026-05-05 — GPU only (Mali-G710 via Play Services TFLite OpenCL). NPU not exposed by Tensor G2. CPU is the runtime fallback when GPU init throws.
3. ~~**Classifier base model:**~~ ✅ RESOLVED 2026-05-09 — DistilBERT-base-uncased. Two-models fallback was tried in M3 Phase F (preflight-only iter 2) and was *worse* than multi-task; MobileBERT not attempted because DistilBERT was within 3-7pp on §7. See `docs/M0_DECISION_MEMO.md` Decision 7.
4. ~~**Single shared base model with two heads vs two separate models:**~~ ✅ RESOLVED 2026-05-09 — single shared encoder + 3 heads (preflight 3-class, memory_presence 2-class, memory_category 6-way multi-label). M3 Phase F iter 2 confirmed multi-task helps preflight by ~3pp precision; memory training acts as encoder regularization. See `docs/M0_DECISION_MEMO.md` Decision 6.
5. ~~**Labeling capacity source:**~~ ✅ RESOLVED 2026-05-07 — solo, synthetic-first via local Ollama (qwen3.5:9b). No two-labeler kappa for v1; deferred to post-launch telemetry-sourced examples per `docs/M3_PLAN.md` §2.
6. **Crash reporting vendor:** OPEN. Firebase Crashlytics, Sentry, or none? Custom content scrubbing required either way.
7. **Localization at launch:** English-only is assumed; if other locales are required, the system prompt's English-locked day-of-week field per SYSTEM_PROMPT.md §4.3 still holds, but UI strings need translation.
8. **Accessibility scope:** Play Store launch standard is TalkBack + dynamic type + color contrast. Anything beyond?
9. **Telemetry endpoint:** Phase 1 needs a minimal backend to receive opt-in aggregate counters. Owned by us, or piggyback on an existing analytics infrastructure?
10. **Dev Brave API key custody:** ✅ RESOLVED in M2 — `android-app/secrets.properties` (gitignored), per CLAUDE.md "Secrets" section.

---

## 9. Suggested team & rough timeline

Sizing for ~26 weeks end-to-end with parallel workstreams:

| Role | FTE | Primary workstreams |
|---|---|---|
| Android engineer (UI + integration) | 1.5 | WS-1, WS-11, WS-12, WS-15 |
| KMP/agent engineer | 1.5 | WS-2, WS-3, WS-4, WS-8, WS-9, WS-10 |
| ML engineer (training + eval) | 1.0 | WS-7, WS-14, partial WS-8 |
| Dataset/labeling lead | 0.5 + labeler capacity | WS-5, WS-6 |
| Product / design | 0.5 | UX flows, copy, store listing |
| QA (later phases) | 0.5 | M5–M7 |
| **Total** | **~5.5 FTE** | |

Calendar: ~6 months from kickoff to public Play Store launch, assuming M0 doesn't surface a blocking issue. The two genuine schedule risks are (a) M0 perf/delegate findings forcing a model swap, and (b) classifier datasets running long.

---

## 10. Decision log

Decisions ratified at planning time, captured here so they don't get re-litigated:

| Decision | Rationale |
|---|---|
| Android-only Phase 1, iOS deferred | Reduces surface area; KMP scaffolding still in place to make Phase 2 cheap. |
| Pixel 7 + Android 16 floor | Narrow target hardware lets us tune memory/perf precisely. |
| Full v1 PRD scope (not a thinner MVP) | Pre-flight classifier and memory are key differentiators; shipping without them undersells the product. |
| KMP from day 1 | A future KMP refactor is more expensive than starting with the right module boundaries; the Android-only constraint costs us little in Phase 1. |
| LiteRT-LM (not MediaPipe LLM Inference) | Per PRD. Reassessed if M0 spike reveals blocking issues. |
| Gemma 4 E4B (not E2B fallback) | Quality matters; relaxed perf targets are acceptable. Re-evaluated only if M0 spike fails. |
| BYOK Brave API + bundled dev key for internal builds | Matches PRD §3.6 for production; unblocks internal testing without per-engineer key procurement. |
| Public Play Store launch as Phase 1 exit criterion | Forces full polish; everything else flows from this. |
