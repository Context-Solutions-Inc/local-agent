# M6 → M7 Handoff Note

**From:** M6 Phase G (2026-05-11)
**To:** M7 — Closed beta → Play Store launch
**Status:** Ready

This is the operational handoff for M6 (polish, eval, telemetry) into M7
(release engineering). M7 doesn't extend the product surface — the
shipped feature set is final for v1 — but it does require landing
several artifacts that M6 deferred so the closed-beta → open-beta →
production pipeline can run. This note enumerates them.

For the full M6 phase log + decisions, see `docs/M6_PLAN.md`. For the
per-phase architecture, see CLAUDE.md's "M6 architecture cheat sheet".

---

## 1. M6 ship state

| Phase | Status | Phase G verification |
|---|---|---|
| A — Schema migration (v1 → v2 → v3, `verifyMigrations` gate) | ✅ host-side complete 2026-05-10 | Live M4 → M6 over-the-top walkthrough **skipped** — host test `MemoriesMigrationTest` covers the full v1→v3 chain; `verifyMigrations=true` is the build-time gate. Documented as accepted in this handoff §6. |
| B — Eager Gemma load + thermal infra | ✅ host-side complete 2026-05-10. **Superseded by PR #25 (2026-05-19)** — eager Gemma load disabled (regex tools + classifier-routed verticals don't need the LLM; fall-through queries pay cold load on first send). Aux engines still warm eagerly. | First-token on Pixel 7 measured ~1–3s subjectively (cold-open-then-send), well under the 5s upper bound. **Target revised from <1.5s to 1–5s acceptable, <5s required** (see §5 Phase G finding 2). `loaded(GPU)` warm-up line confirmed post-launch. |
| C — Telemetry pipeline (4 themed events, opt-in default OFF) | ✅ host-side + on-device complete 2026-05-11 | `Sent(4)` from the debug "Run telemetry upload now" button; all 4 events (`daily_inference`, `daily_preflight`, `daily_search`, `daily_memory`) arrived in Firebase Analytics DebugView. `SkippedConsent` outcome confirmed with consent OFF. |
| D — Crashlytics + ContentRedactor facade | ✅ host-side + on-device verified during Phase D dev | `Bearer <redacted>` confirmed in dashboard during Phase D; breadcrumb redaction confirmed. **Re-verification skipped at Phase G's request** (already user-verified). |
| E — WS-11 polish (onboarding, thermal banner, a11y audit) | ✅ host-side + on-device partial 2026-05-11 | Onboarding (disclosure → Brave key → telemetry) ✅; thermal banner at SEVERE ✅; thermal block at CRITICAL ✅; reset ✅. **TalkBack + dynamic-type 200% walks skipped** at Phase G's request — host-side a11y audit covers the code surface, on-device walks deferred to M7 closed-beta bug-bash. |
| F — Hosted CI + canonical eval | ✅ host-side complete 2026-05-11 | Workflows committed. **PR-trigger end-to-end smoke deferred** to first organic PR that hits the watched paths. |
| G — Integration walkthrough + this handoff | ✅ 2026-05-11 | Bug-bash drills 7, 9 passed (drill 8 idle-unload skipped — M0 plumbing untouched by M6; drill 10 forceUnload-mid-gen retired — no UI button in the M6 Settings surface, host test `InferenceSessionManagerTest` covers the API). |

Test count at end of M6: **318 unit tests** (was 265 at end of M5; +53 in M6 across schema migration, telemetry, redaction, canonical eval).

---

## 2. Telemetry contract (operational)

### Firebase project

- **Project:** Context Solutions production Firebase project.
- **`google-services.json`** lives at `android-app/androidApp/google-services.json`
  — module-level, NOT Gradle-root. The root `.gitignore`'s
  `**/google-services.json` rule keeps it out of git. Each developer
  re-downloads from the Firebase console; CI builds will need a CI-side
  secret store hooked up before any release-track build runs (see §6).

### Wire format

Four themed events, daily windowed (`window_start_epoch_ms` parameter
on every event), sent via WorkManager `TelemetryUploadWorker` on a
24h cadence with `UNMETERED` constraint (no charging gate):

| Event | Counters routed in (by prefix) | Latency metrics |
|---|---|---|
| `daily_inference` | `inference_*`, `first_token_*` | `first_token_ms_{p50,p95,p99}` |
| `daily_preflight` | `preflight_*` | `preflight_ms_{p50,p95,p99}` |
| `daily_search` | `search_*` | `search_ms_{p50,p95,p99}` |
| `daily_memory` | `memory_*` | `retrieval_ms_{p50,p95,p99}` |

`CounterNames` + `LatencyNames` (in `:shared/commonMain/telemetry/`)
pin the wire-format names. Adding a new counter is a
constant-add + a Hilt-provider update — see CLAUDE.md inv. #21 for
the load-bearing gotcha (every `@Provides` site MUST pass
`TelemetryCounters` explicitly; constructor defaults silently no-op
in production).

### DebugView workflow (for in-session validation)

```bash
adb shell setprop debug.firebase.analytics.app com.contextsolutions.mobileagent.debug
```

Then Settings → Anonymous telemetry → "Run telemetry upload now"
(DEBUG-only button) flips `KEY_INCLUDE_CURRENT_WINDOW=true` so the
worker ships today's open window immediately. Without this prop +
button, telemetry takes 24h to reach the BigQuery export.

### Privacy invariants (PRD §4.4 + §3.2.1)

- **Default OFF**, explicit user opt-in via either onboarding screen
  or Settings.
- **No memory content, no message content, no query strings** in any
  event payload. Enforced by `TelemetryPayloadBuilder` reading ONLY
  from `telemetry_aggregate` + `telemetry_latency_aggregate` (never
  `memories` or `messages`). Load-bearing canary test in
  `TelemetryPayloadBuilderTest`: seeds the `memories` + `messages`
  tables with unique markers; asserts neither marker appears in any
  emitted event.
- Single consent toggle gates BOTH Firebase Analytics and Firebase
  Crashlytics. v1.x can split if user feedback warrants.

---

## 3. Crashlytics + redaction discipline

- **Vendor:** Firebase Crashlytics (decision ratified during Phase D —
  Sentry was the comparison, Firebase won on cost-of-vendor since
  Analytics + Crashlytics share the same `google-services.json` +
  consent toggle).
- **Egress facade:** `SafeCrashReporter` interface in
  `:shared/commonMain/observability/`; Firebase impl in
  `:androidApp/.../observability/FirebaseSafeCrashReporter.kt`. **Every
  Crashlytics call MUST go through this facade.** CLAUDE.md inv. #24
  documents the contract; current enforcement is code review + the
  small surface area (2 files in `:androidApp/.../observability/`).
- **Lint rule for direct `FirebaseCrashlytics.getInstance()` calls
  outside the facade: deferred to v1.x.** Phase F was the planned
  landing window; deprioritized in favor of release-engineering work.
  M7 can add a one-line detekt rule if confidence drops.
- **`ContentRedactor`** in `:shared/commonMain/observability/` is the
  single redaction source of truth — used by `SafeCrashReporter`
  AND the HTTP `RedactingLogger`. Patterns: Authorization /
  X-Subscription-Token / bare Bearer tokens / URL query strings.
  Defense-in-depth: **never put user text in exception messages or
  breadcrumbs in the first place** (CLAUDE.md inv. #24).
- **NDK Crashlytics deferred to v1.x.** `firebase-crashlytics-ndk`
  would catch LiteRT-LM JNI / native crashes but adds R8 / ndkBuild
  complexity. v1 ships without native crash capture; M7 can revisit
  once we have JVM crash signal from closed beta.

---

## 4. Canonical eval + hosted CI

Two GitHub Actions workflows shipped in Phase F:

### `.github/workflows/regression-gate.yml` — Classifier regression gate

- **Triggers** on `pull_request` for paths `models/**`, `datasets/**`,
  `classifier-training/**`. Also `workflow_dispatch` for manual runs.
- **Default path** (`pull_request`): `ct-regression-check --skip-eval`
  — SHA-256 verification against `MANIFEST.md` over the regression
  JSONLs. Fast (~30s with pip cache).
- **Full eval path** (`workflow_dispatch` with `full_eval: true`):
  loads torch + checkpoint, runs full `ct-eval-classifier --split
  regression`, diffs against `eval/runs/phaseF_full_*/metrics.json`.
  Use this when a candidate `.tflite` is being prepared for ship.
- **PR-trigger end-to-end test:** deferred to first organic PR that
  hits the watched paths.

### `.github/workflows/prompt-eval-gate.yml` — Canonical routing eval

- **Triggers** on `pull_request` for `SYSTEM_PROMPT.md`,
  `PromptAssembler.kt`, `PreflightRouter.kt`, `QueryRewriter.kt`, the
  `:shared/commonMain/agent/**` tree, and the canonical test files.
- Runs `./gradlew :androidApp:testDebugUnitTest --tests
  "com.contextsolutions.mobileagent.canonical.*"`.
- Single Kotlin test file (`CanonicalEvalTest.kt`) drives 15 canonical
  queries through `PreflightRouter` + `QueryRewriter` +
  `PromptAssembler` with fake classifier outputs (the routing layer is
  what we want to test; classifier accuracy is covered by
  `regression-gate.yml`).
- Failures bundle every regression into one assertion message; failed
  runs upload the test reports as an artifact.

### How to extend the canonical set

In `:androidApp/src/test/.../canonical/CanonicalEvalTest.kt`,
add a new entry to the `canonicalQueries` list. Schema in
`eval/canonical/README.md`. Each entry can specify expected band,
expected rewritten-query substring, memory seed, and prompt-block
presence.

---

## 5. Phase G findings

### Finding 1 — Memory-rewrite chain has a v1.0 boundary case

**Observation.** Query "did my team win last night" with a stored
"my favorite team is the eagles" memory:
- Memory retrieval cosine fell **below the 0.5 threshold** vs the
  verbatim user phrasing, so the memory was NOT injected into Gemma's
  system prompt for this turn.
- Pre-flight classifier returned `p_search_required=0.723` — middle
  band — so the `QueryRewriter`'s possessive-substitution path never
  ran either.
- Gemma asked "which team are you referring to?" instead of using the
  memory.
- Same memory + the query "what's my favorite team" works correctly
  (high cosine match, Gemma answers "the Eagles").

**Root cause.** Three independent thresholds gate the M5 chain:
classifier > 0.85 high band, retrieval > 0.5 cosine, rewriter span
heuristic finds a clean tail. The Phase G case fails on the first two.

**v1.x fix path** (already documented in M5_M6_HANDOFF.md §6):
- **Gemma-generated canonical memory text.** Store
  "User's favorite NFL team is the Philadelphia Eagles" instead of
  "my favorite team is the eagles". The canonical form is entity-rich
  and clears retrieval cosine + rewriter heuristic both. M5
  acknowledged the brittle case; the fix is a brief background Gemma
  call per memory at extraction time (PRD §3.2.4 v1.x note). One-shot
  migration for existing rows.
- **Dataset expansion** to push the classifier's possessive recall
  higher (M3 model card #4).

**M7 implication.** Beta users will hit this case for any
possessive-shaped query against a verbatim-text memory. The
workaround is the `remember that …` prefix at memory-creation time
(forces canonical-ish phrasing) + the user manually disambiguating
in the chat. Document in the closed-beta release notes; **do not gate
launch on this** — it's a documented v1.0 limitation, not a regression.

### Finding 2 — First-token target calibration

The Phase B exit gate aspired to **<1.5s first-token on
cold-open-then-send**. Real Pixel 7 measurement during Phase G
showed **~1–3s subjectively** (sometimes ~1s, sometimes ~3s) — well
under the 4–8s pre-M6 baseline but above the 1.5s aspirational
target. The residual delta is the **first forward-pass prefill** of
the model+system prompt+memory context, which eager warm-up doesn't
preempt (warm-up only loads the model weights; KV cache is empty
until the first generate).

**Revised target (ratified Phase G):** **1–5s acceptable, <5s
required.** Updated in this handoff and PHASE1_PLAN.md §5 M6 row.

### Finding 3 — Drill 10 (Unload button) retired

M1 WS-1 drill 10 ("Tap 'Unload' debug button mid-generation —
forceUnload deferred") referenced a debug button in Settings that no
longer exists in the M6 UI. The underlying `forceUnload(reason)`
API is still load-bearing (production path is
`MobileAgentApplication.onTrimMemory` + the 5-min idle timer); host
test `InferenceSessionManagerTest` covers all `UnloadReason` paths
including the M6 addition of `TrimMemory` (CLAUDE.md inv. #21).
Drill 10 retired from the bug-bash spec; the production path is
covered. M7 can re-add a debug button if closed-beta investigation
needs it.

### Finding 4 — Live M4 → M6 upgrade walkthrough skipped

Phase G deliverable 2 called for installing an M4 build, seeding
memories, then installing M6 over the top to verify schema migration
works on a real install. **Skipped at Phase G's request** because:
- Host test `MemoriesMigrationTest.full_v1_to_v3_migration_chain`
  verifies the full migration chain functionally.
- `verifyMigrations=true` is the build-time gate (would fail
  `assembleDebug` if `Schema.create()` didn't match
  `Schema.migrate(1, 3)`).
- The git-dance + double-build cost (~30 min) is not load-bearing
  given the two host-side proofs above.

**M7 implication.** Beta users coming from a hypothetical M4/M5
build will be doing clean-installs anyway (no production M4/M5
install exists). The first production-relevant upgrade path is
v1.0 → v1.x, which will get its own migration when the time comes.

---

## 6. v1 known weaknesses M7 should bake into release plan

| Weakness | Severity | Where it bites in M7 |
|---|---|---|
| Memory-rewrite chain misses on verbatim-text + middle-band classifier (Finding 1) | Medium | Beta users sending possessive queries against existing memories will see "which team?" responses. Workaround is the `remember that …` prefix. Release notes should call this out. |
| Classifier presence recall 76.8% on relationship-shaped facts | Medium | "My mom's name is X" silently skipped. M5_M6_HANDOFF §3 is the v1.x dataset fix. |
| Possessive substitution span heuristic is brittle | Low | Only matters on FireSearch high-band cases. RewriterAbort fallback returns to M4-like behavior. |
| BLOB JNI cost dominates cosine at 1k+ memories | Low | Phase 1's 1k-row cap holds. v1.x pre-load fix in M5_M6_HANDOFF §4a. |
| `temporary_context` expiry parser misses month-name dates | Low | Falls back to 30-day default. Acceptable. |
| No NDK Crashlytics (no native crash capture) | Medium | LiteRT-LM JNI crashes won't surface in dashboard. JVM crashes are captured. Add in v1.x after closed-beta crash signal is in hand. |
| No lint rule blocking direct `FirebaseCrashlytics.getInstance()` | Low | Code-review discipline holds; small surface area. v1.x detekt rule. |
| Live M4 → M6 upgrade walkthrough not run (Finding 4) | Low | No production M4/M5 install exists. Host tests cover. |

---

## 7. M6 deliverables M7 inherits

| Path | Purpose |
|---|---|
| `:shared/commonMain/inference/ThermalStatusProvider.kt` | Interface + `ThermalStatus` enum |
| `:shared/androidMain/inference/AndroidThermalStatusProvider.kt` | `PowerManager` listener wrapper |
| `:shared/commonMain/telemetry/TelemetryCounters.kt` + `CounterNames` + `LatencyNames` | Counter API + wire-format names |
| `:shared/commonMain/telemetry/TelemetryConsentManager.kt` | Opt-in toggle (default OFF) |
| `:shared/commonMain/telemetry/TelemetryPayloadBuilder.kt` | SQL → 4 themed events; memory-exclusion canary in test |
| `:shared/androidMain/telemetry/InMemoryTelemetryCounters.kt` | ConcurrentHashMap + ReservoirSampler |
| `:androidApp/.../telemetry/FirebaseAnalyticsSink.kt` | Firebase egress |
| `:androidApp/.../service/TelemetryUploadWorker.kt` | 24h WorkManager periodic |
| `:shared/commonMain/observability/SafeCrashReporter.kt` + `ContentRedactor.kt` | Crashlytics facade + redaction |
| `:androidApp/.../observability/FirebaseSafeCrashReporter.kt` | Firebase Crashlytics impl |
| `:shared/commonMain/onboarding/OnboardingPreferences.kt` | First-run state |
| `:androidApp/.../app/ui/onboarding/` | 3 onboarding screens + host |
| `:androidApp/.../app/ui/chat/ThermalBanner.kt` | SEVERE/CRITICAL UI surface |
| `:shared/commonMain/sqldelight/.../db/1.sqm` + `2.sqm` | v1→v2 (access_count+indexes), v2→v3 (telemetry tables) |
| `:shared/commonMain/sqldelight/databases/{1,2}.db` | Schema snapshots for `verifyMigrations` |
| `:androidApp/src/test/.../canonical/CanonicalEvalTest.kt` | 15-query routing-layer regression gate |
| `eval/canonical/README.md` | Canonical-set schema docs |
| `.github/workflows/regression-gate.yml` | Classifier SHA gate (+ manual full eval) |
| `.github/workflows/prompt-eval-gate.yml` | Prompt/routing canonical gate |
| `docs/PRIVACY_POLICY.md` | Privacy policy draft (needs public URL — see §8) |
| `docs/DATA_SAFETY_NOTES.md` | Play Console Data Safety cheat sheet |

---

## 8. Open M7 items

### Release engineering (the actual M7 work)

| Item | Notes |
|---|---|
| **Android 16 GA on Pixel 7** | PHASE1_PLAN §8 Q1 still open. Confirms the addressable user base. If A16 isn't GA at launch, internal/closed beta runs on developer-preview channel; open beta + production wait for GA. |
| **Play Console: internal testing track** | Sign up + configure with ~5–10 internal testers. APK build pipeline needs a release-keystore secret store. |
| **Play Console: closed testing** | ~50–200 testers. Gather via existing Context Solutions network + dogfooding. |
| **Play Console: open testing** | Public-but-flagged. Gates on closed-beta crash-free rate + UX feedback. |
| **Play Console: production** | Final track. |
| **Store listing copy** | App name, short description, full description, "What's new" first entry. Highlight: on-device LLM, no conversations leave the device, Brave-only outbound. |
| **Store screenshots** | 5–8 screenshots for phone form factor. Onboarding disclosure + chat + memory screen + settings + thermal banner are the candidates. |
| **Feature graphic** | 1024×500 Play Store banner. |
| **Signing key custody** | Generate the release keystore; store in 1Password (or equivalent) + a sealed backup. **Do not commit to git.** Play App Signing should be enrolled — Google manages the upload-key separation. |
| **Privacy policy public URL** | `docs/PRIVACY_POLICY.md` is the draft. Needs hosting. Options: (a) GitHub Pages of this repo, (b) Context Solutions company site, (c) static page on the Firebase project. Pick + publish before Play Console submission. |
| **Data Safety form submission** | `docs/DATA_SAFETY_NOTES.md` is the cheat-sheet. Fill via Play Console. Key claims: "We collect: anonymous usage stats (counters only, opt-in)." "We share: nothing." "Search queries to Brave are user-initiated and routed by the app." |
| **Content rating** | IARC questionnaire via Play Console. Should rate as low-impact (no UGC sharing, no user-to-user chat). |
| **On-device LLM disclosure** | Play Console review may flag the "AI model" surface — be ready to clarify it's bundled inference, not generative-AI-as-a-service. |
| **Beta tester recruitment plan** | Source for the 50–200 closed-beta cohort. |
| **Crash-free session rate gate** | PRD §7 target: >99.5%. Closed beta should hold this gate before open-beta promotion. |

### M6 work deferred to v1.x (NOT v1 launch blockers)

| Item | M6 plan reference |
|---|---|
| Offline indicator chip in chat top bar | M6_PLAN §3.5 / Phase E §6 |
| "Ready" interstitial after model download | Phase E §1 (cut in execution) |
| NDK Crashlytics (`firebase-crashlytics-ndk`) | Phase D §1 |
| Detekt lint rule blocking direct `FirebaseCrashlytics` access | Phase D §8 / Phase F |
| Feedback UI (thumbs-up/down on responses) | M6 §8 — would feed v1.x classifier-precision telemetry |
| Conversation persistence (chat history survives kill) | M5_M6_HANDOFF §9 |
| `MemoryStore` Flow extension (replace `LaunchedEffect` polling) | M5_M6_HANDOFF §9 |
| Memory-creation undo snackbar (5s) | M5_M6_HANDOFF §9 |
| Gemma-generated canonical memory text (closes Finding 1) | M5_M6_HANDOFF §6 |
| In-place memory text editing | M5_M6_HANDOFF §6 |
| Per-category memory-disable toggles | M5_M6_HANDOFF §6 |
| Embedding pre-load cache (cosine perf at 1k+ rows) | M5_M6_HANDOFF §4a |
| Embedder/classifier int32 input re-export (latency win) | M5_M6_HANDOFF §4b + model card #5 |
| MiniLM + classifier GPU delegate path | Blocked on `BROADCAST_TO`/`EMBEDDING_LOOKUP` runtime support |

---

## 9. PRD §4.4 + §7 coherence check

Re-read against the shipped telemetry + Crashlytics contract:

### §4.4 (Privacy and security)

| PRD requirement | Shipped state |
|---|---|
| "User conversations and memories never leave the device." | ✅ `TelemetryPayloadBuilder` reads only aggregate tables. Canary test in place. |
| "Crash reporting must be opt-in and must scrub all message and memory content." | ✅ `SafeCrashReporter` facade + `ContentRedactor`. Single consent gate. **Caveat:** redactor is defense-in-depth — primary discipline is "never put user text in exception messages in the first place" (CLAUDE.md inv. #24). Code review enforces; M7 detekt rule would harden. |
| "Brave API key stored only in platform secure storage." | ✅ M2's `SecureStorage` (`EncryptedSharedPreferences`) holds the key. |
| "App must not include any user identifier, device identifier, or conversation context in [Brave] requests." | ✅ Search payload is the rewritten query string only. Memory-derived substitution is allowed (PRD explicit) but raw memory text is not appended. |
| "Memory storage on disk protected by file-based encryption." | ✅ Android's standard FBE applies to internal app storage (where the SQLite DB lives). iOS Phase 2 will need `NSFileProtectionCompleteUntilFirstUserAuthentication`. |

### §7 (Success metrics)

| Metric | Target | Status at end of M6 |
|---|---|---|
| Time to first token | p50 < 1.5s, p90 < 2.5s | **Calibrated to 1–5s acceptable, <5s required** (Phase G Finding 2). Real Pixel 7 measurement is in this range. |
| Search-tool invocation rate | 20–40% of queries | Telemetry shipped; measurement starts at closed beta. |
| Pre-flight classifier precision | 95%+ on high-confidence band | v1.0 ships at 91–93% on this band (M3 §7 gate FAIL by 2–4pp). Documented in model card; closes via dataset expansion in v1.x. |
| Pre-flight classifier recall | 90%+ on time-sensitive | v1.0 within range; see M3 model card. |
| Pre-flight hit rate | 70%+ of queries that ultimately invoke search | Telemetry shipped. Measurement at closed beta. |
| Memory retrieval relevance | 80%+ labeler-rated | No offline eval set yet (v1.x); user-facing memory screen is the v1 review surface. |
| Memory creation precision | 90%+ user-rated useful | No feedback UI yet (deferred to v1.x). |
| Crash-free session rate | >99.5% | Crashlytics shipped; measurement at closed beta. |

**Drift flagged for M7 release notes:**
- First-token target relaxed (Finding 2).
- Pre-flight precision below PRD target; documented v1.x improvement path.
- Memory-relevance + memory-precision metrics need the feedback UI before they're measurable. Closed beta should bake in user feedback collection so v1.x can land the targeted dataset improvements.

---

## 10. Things M6 explicitly did NOT do (and won't in v1)

- Pre-flight thresholds hot-swappable from server. They're in
  `preflight_config.json` shipped in the APK; updates require an APK
  release.
- Multi-device memory sync (PRD §4.4 hard constraint).
- iOS targets (Phase 2).
- Native vector index (HNSW/FAISS). Brute-force cosine is fine at 1k rows.
- Conversation export.
- Tool surface beyond `web_search`.
- Multi-modal input.

PRD §9 has the full list.

---

## 11. Quick-start for M7 kickoff

When a fresh Claude session opens for M7:

1. Read this file.
2. Read `docs/M6_PLAN.md` §1, §2, §4 Phase G for the M6 ship state.
3. Read `PHASE1_PLAN.md` §5 M7 row + §6 Pixel 7 + §8 Q1 (Android 16 GA).
4. Read `PRD.md` §4.4 + §7 (closed-beta gate criteria).
5. Read `docs/PRIVACY_POLICY.md` + `docs/DATA_SAFETY_NOTES.md` (drafts to finalize).
6. Read CLAUDE.md "M6 architecture cheat sheet" + hard invariants 20–27.

First M7 working session: pick the smallest item from §8 — likely
"generate release keystore + enroll Play App Signing" — and ship
it. The internal-testing-track APK upload is the smallest end-to-end
exercise of the release pipeline.
