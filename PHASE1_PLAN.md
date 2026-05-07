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
| Pre-flight classifier inference | <50 ms p95 | <80 ms p95 | (M3) |
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

Outstanding M0 items deferred to M1:
- Decision 3 (foreground service contract) — needs chat surface to test.
- Power state / ambient temperature for the on-record run (engineer to confirm).
- 16 KB native page alignment check on `liblitertlm_jni.so` (the spike ran
  successfully, suggesting alignment is fine; explicit verification still
  recommended).

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
  post-processing per PRD §3.3 (top-3 organic, ≤200 char snippets, ≤2KB total
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
in them. Richer snippet handling is M2.1+ scope.

Phase A (WS-2 schemas + WS-12 secure-key plumbing) shipped first, then Phase B
(Brave client + cache + post-processor), then Phase C (agent types + prompt
assembler + ReAct loop), then Phase D (UI + settings + nav), then a
multi-round bugfix sequence to get the LiteRT-LM tool-calling integration
right (the architecture had to shift from text-marker parsing to LiteRT-LM's
structured tool API; one `Conversation` per turn rather than per generate).

### M3 — Datasets & classifier training (weeks 4–14, runs largely in parallel with M1/M2)

This is the longest critical-path workstream and starts on day one.

- **WS-5:** Pre-flight dataset reaches 12,000 examples per CLASSIFIER_DATASETS.md §2. Three-phase sourcing: synthetic generation via frontier model (~10k), in-house adversarial authoring (~2k), with two-labeler agreement at 85%+ Cohen's kappa. Includes the 800-example adversarial pair set per §2.4 and 30%+ naturalistic phrasings per §2.5.
- **WS-6:** Memory extraction dataset reaches 8,000 examples per §3. Includes ≥200 explicit-forget and ≥200 explicit-remember examples per §3.5.
- **WS-7:** Base model selection (MobileBERT vs DistilBERT vs MiniLM-with-classification-head). Two heads sharing one base if architectures align (decided by accuracy/footprint tradeoff). Fine-tune, INT8 quantize, convert to LiteRT, benchmark on Pixel 7 to validate <80 ms p95 inference.
- **Deliverable:** Two LiteRT classifier artifacts in `/models`. Test-set metrics meet PRD §7 targets (95%+ precision on high-confidence pre-flight, 90%+ recall, 90%+ memory extraction precision) — failure here triggers dataset expansion before integration.

### M4 — Pre-flight classifier integration (weeks 12–16)

- **WS-8:** Classifier inference in shared layer. Three-band routing: >0.85 → fire pre-flight search, <0.15 → skip, middle → fall through to Gemma's own tool-call decision. Query rewriter: deterministic rules for date and abbreviation expansion, Gemma fallback for complex rewrites, abort-and-fall-through if rewriter is not confident. `[PRE-FLIGHT NOTICE BLOCK]` in system prompt per SYSTEM_PROMPT.md §6.
- **WS-14:** Pre-flight regression set + offline eval harness wired to CI. Threshold tuning surface (configurable JSON shipped with app per PRD §3.2.1).
- **Deliverable:** Pre-flight is short-circuiting common search queries with measurable latency improvement. Regression set acts as a release gate.

### M5 — Memory subsystem (weeks 14–18)

- **WS-9:** all-MiniLM-L6-v2 INT8 embedder integrated. Memory storage: SQLite table per PRD §3.2.4 with embeddings stored as BLOBs. **Brute-force cosine over up to 1,000 entries — no native vector index needed at this scale (sub-10ms on Pixel 7 for 1k × 384-dim).** Retrieval with K=5 / threshold 0.5, expiration filter, recency weighting. Eviction policy: expired → 90-day-stale → LRU+frequency.
- **WS-10:** Background memory extraction job after each user turn. Templated candidate generation (Gemma-based generation deferred to v1.x per PRD §3.2.4). Embedding-based dedup (cosine > 0.85). Explicit remember/forget command detection routes through the same classifier with a different label head.
- **WS-11:** Memory management UI: list all memories grouped by category, edit/delete individual entries, clear all, disable creation toggle. Per-conversation indicator showing memories created in that conversation.
- **WS-12:** Verify memory database file is on `NSFileProtectionCompleteUntilFirstUserAuthentication`-equivalent (Android FBE Credential Encrypted Storage). No memory content in any log path or telemetry payload.
- **Deliverable:** Persistent memory across conversations, fully transparent and user-controllable per PRD §3.2.4.

### M6 — Polish, eval, telemetry (weeks 18–22)

- **WS-11:** First-run UX polish, accessibility pass (TalkBack, dynamic type, color contrast), all error states from PRD §6.2 (no key, offline, no model, low storage, thermal critical, Brave 4xx/5xx). Thermal-state warnings in UI per PRD §4.3.
- **WS-13:** Opt-in telemetry. Off by default. Aggregate counters only — no query strings, no memory content, no conversation text. Explicit consent screen with itemized list of what is and is not transmitted.
- **WS-14:** Full eval harness gates classifier and system-prompt changes via CI. Canonical query set per SYSTEM_PROMPT.md §11.
- **WS-15:** Crashlytics with aggressive content scrubbing (custom log redactor). Performance telemetry: model load time, first-token p50/p95, search latency, pre-flight hit rate. Data Safety form drafted, privacy policy drafted and reviewed.
- **Deliverable:** Internal-quality build ready for closed beta.

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

| Risk | Severity | Mitigation |
|---|---|---|
| Gemma 4 E4B doesn't fit perf/memory envelope on Pixel 7 even with relaxed targets | High | M0 spike validates early. Fallback: drop to E2B Q4 (~1.5 GB). Decision gate at end of M0. |
| LiteRT-LM API churn breaks integration mid-Phase 1 | Medium | Pin to specific release. Isolate behind `InferenceEngine` interface. Keep MediaPipe LLM Inference API as a documented escape hatch. |
| Classifier accuracy misses PRD §7 targets after training | High | Dataset construction starts day one (M3 starts in week 4, not week 14). Adversarial pair set is a quality gate. Iterate on dataset if first training pass misses. Hold integration (M4) until thresholds met. |
| Dataset construction blocks classifier integration | High | Three-phase sourcing per CLASSIFIER_DATASETS.md §2.6 starts in M0. Lock labeler capacity early — vendor (Scale, Surge) or in-house team commitment by week 2. |
| Pixel 7 thermal throttling under sustained inference | Medium | Thermal monitoring + token-rate throttling in WS-1. UI warnings per PRD §4.3. |
| Play Store privacy/data-safety review of an on-device LLM | Medium | Data Safety form clearly states only Brave queries leave device. Privacy policy explicit about on-device processing. Telemetry is opt-in and explicitly excludes content. Engage Play Console review early in M7. |
| Brave API costs scale beyond free tier in dev/test | Low | Aggressive caching is already specified. Internal builds rate-limit to development quotas. Production is BYOK so per-user costs are user-borne. |
| Gemma 4 model artifact availability/licensing changes | Medium | Confirm distribution rights and CDN hosting plan in M0. Have a checksum-pinned download URL under our control, not Google's. |
| Tensor G2 NPU delegate not available in LiteRT-LM | High | M0 spike validates. Fallback to GPU delegate. If both insufficient, downshift to E2B. |
| 8 GB RAM headroom too tight when other apps are running | Medium | Aggressive `onTrimMemory` handler unloads Gemma 4 immediately on system pressure. Cold reload at 4–8s is acceptable per relaxed targets. |

---

## 8. Open questions

These need answers before or during M0:

1. **Android 16 GA on Pixel 7:** What is the production rollout date? If not GA at our launch, what is the addressable Pixel 7 + Android 16 user base?
2. **LiteRT-LM Tensor G2 delegate:** Confirmed supported, GPU-only, or CPU-only? (M0 spike answers this.)
3. **Classifier base model:** MobileBERT, DistilBERT, or MiniLM-with-head? (Decided in M3 by accuracy/latency tradeoff on Pixel 7.)
4. **Single shared base model with two heads vs two separate models** for pre-flight + memory extraction — the PRD leaves this open (§3.2.4) and it has direct memory-budget implications (~50 MB savings if shared).
5. **Labeling capacity source:** in-house team, contract labelers, or vendor (Scale AI, Surge AI, Snorkel)? Cost and timeline depend on this.
6. **Crash reporting vendor:** Firebase Crashlytics, Sentry, or none? Custom content scrubbing required either way.
7. **Localization at launch:** English-only is assumed; if other locales are required, the system prompt's English-locked day-of-week field per SYSTEM_PROMPT.md §4.3 still holds, but UI strings need translation.
8. **Accessibility scope:** Play Store launch standard is TalkBack + dynamic type + color contrast. Anything beyond?
9. **Telemetry endpoint:** Phase 1 needs a minimal backend to receive opt-in aggregate counters. Owned by us, or piggyback on an existing analytics infrastructure?
10. **Dev Brave API key custody:** which engineer/account holds the dev key, where is `secrets.properties` stored, what's the rotation policy?

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
