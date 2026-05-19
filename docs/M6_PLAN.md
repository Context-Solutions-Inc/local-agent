# M6 — Polish, Eval, Telemetry: Implementation Plan

**Document version:** 0.1 (Draft)
**Status:** Awaiting Phase A kickoff
**Last updated:** 2026-05-10
**Companion to:** PRD.md §3.2.1 / §4.3 / §4.4 / §6 / §7, PHASE1_PLAN.md §5 M6 (WS-11 / WS-13 / WS-14 / WS-15 + M5 carry-overs), `docs/M5_M6_HANDOFF.md`, `docs/M6_KICKOFF.md`, SYSTEM_PROMPT.md §11

> **2026-05-19 — PR #25 reversal.** Eager Gemma load (Phase B) was disabled.
> Regex tools (clock/todo/memory) and classifier-routed verticals (weather)
> don't need the LLM at all; for fall-through queries the user accepts the
> first-send cold-load. `InferenceSessionManager.warmUpIfPossible`,
> `WarmUpOutcome`, the `INFERENCE_WARMUP_*` counters, the thermal/memory
> gates on warm-up, and `idleTimeoutAfterWarmUp` were all removed. Aux
> engines (classifier + embedder) still warm eagerly on Chat RESUME via
> `MainViewModel.warmUpAuxEngines`. The thermal gating + first-token
> targets below are historical context for the warm-up path; the steady-
> state generate() path is unchanged.

---

## 1. Goal

Make the v1 feature surface shippable. M5 finished the feature build — chat,
search, pre-flight, memory all working on Pixel 7 within budget. M6 wraps the
production scaffolding around it:

- Schema migration so M5+ builds can install over existing dev installs without
  `pm clear`.
- Eager Gemma load so first-token latency on cold open drops from ~5 s to < 1 s.
- Opt-in counter-only telemetry pipeline (Firebase Analytics) with explicit consent.
- Firebase Crashlytics + content scrubbing.
- Hosted CI for `ct-regression-check` + a canonical-query eval harness for the
  system prompt (SYSTEM_PROMPT.md §11).
- First-run onboarding (3 screens), accessibility pass, PRD §6.2 error states,
  thermal-state UI.

Exit criterion: **internal-quality build ready for closed beta (M7).**

### Exit criteria (M6 done = all true)

| # | Criterion | Source |
|---|---|---|
| 1 | `MobileAgentDatabase.Schema.version = 2`, `.sqm` migration captures `access_count`; existing M2/M4 installs upgrade in place without data loss | M5_M6_HANDOFF §2 |
| 2 | `verifyMigrations = true` in SQLDelight Gradle block (build-time schema-drift gate) | hygiene |
| 3 | Eager Gemma load fires on Chat navigation; first-token p50 on cold-open-then-send < 1.5 s (vs. ~5 s baseline) | M6_KICKOFF, PRD §4.1 |
| 4 | Eager load skips at thermal SEVERE/CRITICAL | PRD §4.3 |
| 5 | Telemetry toggle defaults OFF; turning ON sends daily aggregate counters to Firebase Analytics; turning OFF stops transmission within 1 cycle | PRD §3.2.1, §4.4 |
| 6 | Telemetry payload builder unit-tested to never read `memories` or `messages` tables | PRD §4.4, M5_M6_HANDOFF §1 |
| 7 | First-run flow: disclosure → Brave key → telemetry consent → download → ready (5 screens) | PRD §6.1 |
| 8 | Telemetry consent screen reachable from Settings; both surfaces honor the same `TelemetryConsentManager` | user Q4 |
| 9 | All PRD §6.2 error states demonstrated on Pixel 7: no Brave key, offline, no model, low storage, thermal critical, Brave 4xx/5xx | PRD §6.2 |
| 10 | Firebase Crashlytics wired with `SafeCrashReporter` facade; manual deliberate-leak test confirms redaction at egress | PRD §4.4 |
| 11 | Performance counters land in telemetry: first-token p50/p95, preflight band counts, search invocation rate, memory retrieval p95 | PRD §7 |
| 12 | Thermal banner at SEVERE; full-screen block + disabled send at CRITICAL | PRD §4.3 |
| 13 | Accessibility: TalkBack reads every interactive surface; dynamic type at 200% doesn't break layout; WCAG AA contrast | user Q9 |
| 14 | Hosted CI runs `ct-regression-check --skip-eval` on every PR touching `models/`, `datasets/`, or `classifier-training/`; full eval on classifier-update PRs via `workflow_dispatch` | PHASE1_PLAN WS-14 |
| 15 | Canonical query set (`eval/canonical/canonical_v1.0.0.yaml`) lands; `ct-eval-prompt` driver gates prompt changes via CI | SYSTEM_PROMPT.md §11 |
| 16 | Data Safety form drafted; privacy policy drafted; both reviewed | PRD §4.4, M7 prereq |
| 17 | M5 regression suite still green (265 unit + 4 instrumentation); no behavioral change to classifier engine / embedder engine / agent loop core | regression |

Failure on (1), (5), (6), (9), or (10) blocks M7. (3), (12), (13) are user-facing closed-beta quality bars.

---

## 2. Decisions ratified at planning time

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | Crash reporting vendor | **Firebase Crashlytics** | Q1 answered. Gradle plugin auto-instruments NDK crashes; Firebase project shared with telemetry (Decision 2). Trade-off vs Sentry: no `beforeSend` egress hook, so redaction lives at the call site behind a `SafeCrashReporter` facade. |
| 2 | Telemetry endpoint | **Firebase Analytics custom events** (multiple themed events to fit the 25-parameter cap), auto-export to BigQuery | Q2 answered. Zero backend to maintain; Firebase project already in use for Crashlytics. Themed events (`daily_preflight`, `daily_memory`, `daily_search`, `daily_inference`) keep us under the per-event parameter cap and leave room for v1.x growth. |
| 3 | First-run scope | **3 onboarding screens** + existing download progress + "ready" screen | Q3 answered. Disclosure (a) → Brave key (b, skippable) → telemetry consent (c). Anything richer deferred to v1.x. |
| 4 | Telemetry consent placement | **First-run + Settings (both)** | Q4 answered. First-run is the only honest opt-in moment; Settings is the only honest opt-out moment. Both surfaces talk to the same `TelemetryConsentManager`. |
| 5 | Aggregate memory counts in telemetry | **In-scope** (counts only — no IDs, no text, no per-memory rows) | Q5 answered. PRD §4.4 forbids memory content; aggregate counts are non-reidentifying. Wired via separate counter API, not via `MemoryRetriever`/`MemoryExtractor` loggers (those stay text-aware per M5 WS-12). |
| 6 | Schema migration sequencing | **Phase A — before closed beta** | Q6 answered. Don't ship anything that depends on a stable DB shape until migration is wired. Subsequent phases assume the v2 schema. |
| 7 | Android 16 GA on Pixel 7 | **Confirmed GA** | Q7 answered. Removes the PHASE1_PLAN §8 #1 launch-date risk. |
| 8 | Localization | **English only for v1** | Q8 answered. UI strings, error copy, TalkBack labels all English. The SYSTEM_PROMPT.md §4.3 English-locked day-of-week is preserved. |
| 9 | Accessibility scope | **TalkBack labels on every interactive surface; dynamic type up to 200%; WCAG AA color contrast** | Q9 answered. Nothing beyond. |
| 10 | Eager Gemma load — trigger location | **`MainScreen`-level `LaunchedEffect` keyed off `currentRoute is Chat`** | Survives configuration changes; fires once per navigation; doesn't re-fire on rotation or dark-mode toggles. |
| 11 | Eager Gemma load — debounce | **300 ms** between navigation-to-Chat and `warmUp()` call | Catches Settings→Chat→Settings flips without paying the cold-load cost; if user leaves Chat before debounce fires, the load is skipped. |
| 12 | Eager Gemma load — thermal gate | **Skip eager load at THERMAL_STATUS_SEVERE (3) or CRITICAL (4)** | Device is already throttling; user pays cold-load on first `send()` if they proceed. Cleared by Phase B telemetry counter `inference_warmup_skipped_thermal_total`. |
| 13 | Eager Gemma load — unload | **No new unload mechanism**; existing 5-min idle (M0 Decision 5) + `onTrimMemory()` proactive unload stay in place | The "Settings → Chat → Settings" flip the kickoff worried about is already handled correctly by the existing mechanisms (Settings is foreground, doesn't accelerate idle). Adding Activity-`onPause` unload thrashes on short navigations. |
| 14 | Telemetry payload shape | **Counter aggregates per 24-hour window**, latencies as p50/p95/p99 summary stats (not histograms) | Lighter to build; matches PRD §7's percentile-only target list. Histograms are a v1.x option. |
| 15 | Feedback-derived telemetry | **Deferred to v1.x** (classifier-precision / memory-creation-precision / search-usefulness signals) | v1 has no thumbs-up/down UI. Telemetry pipeline is the chassis; the feedback affordance is the v1.x add. |
| 16 | Thermal UI | **Silent at LIGHT/MODERATE (0/1)**, dismissible **banner above input at SEVERE (2/3)**, **full-screen block + disabled send at CRITICAL (4)** | PRD §4.3 says "warned at critical" — disable is the right interpretation; alternative is OS killing the app mid-generation. |
| 17 | WS-14 hosted CI runner | **GitHub Actions, GitHub-hosted runners** | Avoids self-hosted operational tax. SHA-only gate runs every PR (~30 s with cached venv); full eval runs on classifier-update PRs only (via `workflow_dispatch`, GPU not in standard GHA so manual trigger is fine for the low-frequency event). |
| 18 | Crashlytics content scrubbing | **`SafeCrashReporter` facade at call site** wrapping `FirebaseCrashlytics.recordException`; `ContentRedactor` extracted from M2's `RedactingLogger` and reused | Crashlytics has no `beforeSend` hook (vs Sentry). The facade is the single chokepoint; direct `FirebaseCrashlytics.recordException` outside the facade is a lint failure. |
| 19 | Conversation message persistence | **Deferred to v1.x** | M5_M6_HANDOFF §9 lists this as an M6 "should add" but it's not on the path to closed beta. ChatViewModel's in-memory `agentHistory` is fine for v1; persistent history is a polish item. |
| 20 | Memory store `Flow` extension | **Deferred to v1.x** | M5_M6_HANDOFF §9. `LaunchedEffect(Unit) → refresh()` is fine for v1. Reactive Flow becomes valuable when telemetry backfill or sync arrives. |

---

## 3. Architectural seams

New code and the existing files M6 touches. Signatures illustrative.

### 3.1 Schema migration (Phase A)

```
:shared/commonMain/sqldelight/migrations/1.sqm  (NEW)
    -- Captures the v1 → v2 delta: adds access_count column.
    ALTER TABLE memories ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0;

:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/Memories.sq  (EDIT)
    -- Bump the embedded version comment to v2 to match.

:shared/androidMain/.../db/AndroidDatabaseFactory.kt  (EDIT)
    AndroidSqliteDriver(MobileAgentDatabase.Schema, context, "mobile_agent.db",
                        callback = MIGRATIONS_CALLBACK)

:shared/build.gradle.kts  (EDIT)
    sqldelight {
      databases {
        create("MobileAgentDatabase") {
          packageName.set("com.contextsolutions.mobileagent.db")
          verifyMigrations.set(true)   // ← Phase A
        }
      }
    }
```

### 3.2 Eager Gemma load (Phase B)

```
:androidApp/.../app/MainScreen.kt  (EDIT)
    LaunchedEffect(currentRoute) {
      if (currentRoute is Route.Chat) {
        delay(EAGER_LOAD_DEBOUNCE_MS)        // 300
        if (currentRoute is Route.Chat) {     // re-check after debounce
          sessionManager.warmUpIfPossible()
        }
      }
    }

:shared/androidMain/.../inference/InferenceSessionManager.kt  (EDIT)
    suspend fun warmUpIfPossible(): WarmUpOutcome {
      val thermal = thermalStatusProvider.current()
      return when {
        state.value is Loaded -> WarmUpOutcome.AlreadyLoaded
        thermal >= THERMAL_STATUS_SEVERE -> WarmUpOutcome.SkippedThermal
        else -> { loadModel(...); WarmUpOutcome.Loaded }
      }
    }

:shared/commonMain/inference/ThermalStatusProvider.kt  (NEW, expect)
:shared/androidMain/inference/AndroidThermalStatusProvider.kt  (NEW, actual)
    PowerManager.currentThermalStatus, with a Flow for observers.
```

### 3.3 Telemetry pipeline (Phase C)

```
:shared/commonMain/telemetry/  (NEW package)
    TelemetryCounter.kt        — interface: counter("name").increment()
                                 — interface: counter("name").observe(latencyMs)
    TelemetryConsentManager.kt — expect/actual; reads + writes the boolean
                                 toggle and a one-shot first-run-decided flag
    TelemetryPayloadBuilder.kt — reads telemetry_aggregate table + rolling
                                 latency state, builds Firebase event payloads
                                 (themed: daily_preflight, daily_memory,
                                 daily_search, daily_inference)
    TelemetryUploader.kt       — fires the events via FirebaseAnalytics.logEvent;
                                 platform-specific actuals
    AtomicCounterRegistry.kt   — in-memory counters, flushed to SQL at session
                                 end or 24 h boundary

:shared/commonMain/sqldelight/.../db/TelemetryAggregate.sq  (EDIT or NEW)
    -- 24-hour roll-up table. Schema in §3.3.1 below.

:shared/androidMain/telemetry/AndroidFirebaseAnalyticsBridge.kt  (NEW)
    actual fun logDailyPreflight(params: Map<String, Long>) =
      FirebaseAnalytics.getInstance(context).logEvent("daily_preflight", bundleOf(params))

:androidApp/.../app/ui/onboarding/TelemetryConsentScreen.kt  (NEW)
:androidApp/.../app/ui/settings/TelemetrySection.kt  (NEW component in Settings)

:shared/commonMain/agent/AgentLoop.kt  (EDIT, additive)
    counters.increment("queries_total")
    // ... at each decision point

:shared/commonMain/classifier/PreflightRouter.kt  (EDIT, additive)
    counters.increment("preflight_${band}_total")

:shared/commonMain/memory/MemoryExtractor.kt / MemoryRetriever.kt / MemoryEvictor.kt
    (EDIT, additive — counter calls separate from the existing text-aware logger)
```

#### 3.3.1 `telemetry_aggregate` schema

```sql
CREATE TABLE telemetry_aggregate (
    window_start_epoch_ms INTEGER NOT NULL,    -- aligned to UTC day boundary
    counter_name          TEXT    NOT NULL,
    counter_value         INTEGER NOT NULL,
    PRIMARY KEY (window_start_epoch_ms, counter_name)
);

CREATE TABLE telemetry_latency_aggregate (
    window_start_epoch_ms INTEGER NOT NULL,
    metric_name           TEXT    NOT NULL,    -- "first_token_ms", "preflight_ms", ...
    p50_ms                INTEGER NOT NULL,
    p95_ms                INTEGER NOT NULL,
    p99_ms                INTEGER NOT NULL,
    sample_count          INTEGER NOT NULL,
    PRIMARY KEY (window_start_epoch_ms, metric_name)
);
```

Latency percentiles are computed via reservoir sampling (1024-sample reservoir per metric per day → memory-bounded, single-pass).

#### 3.3.2 Counter inventory (v1)

| Event | Parameter | Source |
|---|---|---|
| `daily_inference` | `queries_total` | `AgentLoop` start |
| | `first_token_p50_ms`, `_p95_ms`, `_p99_ms` | `LiteRtInferenceEngine.generate` |
| | `inference_warmup_eager_total` | `InferenceSessionManager.warmUpIfPossible` |
| | `inference_warmup_skipped_thermal_total` | thermal-gated |
| | `inference_warmup_skipped_loaded_total` | already loaded |
| | `inference_unloaded_idle_total` | 5-min timer fires |
| | `inference_unloaded_trim_memory_total` | `onTrimMemory` callback |
| | `thermal_severe_seconds`, `_critical_seconds` | thermal observer |
| `daily_preflight` | `preflight_high_band_total` | `PreflightRouter.route` |
| | `preflight_middle_band_total` | |
| | `preflight_low_band_total` | |
| | `preflight_rewriter_abort_total` | |
| | `preflight_classifier_unavailable_total` | |
| | `preflight_p50_ms`, `_p95_ms`, `_p99_ms` | |
| `daily_search` | `search_invoked_total` | `SearchService.search` |
| | `search_cache_hit_total` | |
| | `search_error_total{kind}` (kind = network/4xx/5xx/timeout) | |
| | `search_disabled_total` | toggle off |
| | `search_no_key_total` | key missing |
| | `search_p50_ms`, `_p95_ms`, `_p99_ms` | |
| `daily_memory` | `memory_extracted_total` | `MemoryExtractor.extract` insert path |
| | `memory_dedup_skipped_total` | cosine > 0.85 hit |
| | `memory_forgotten_total` | `handleForget` delete |
| | `memory_evicted_total{tier}` (expired/stale/lru) | `MemoryEvictor` |
| | `memory_retrieved_total` | `MemoryRetriever.retrieveTopK` non-empty |
| | `memory_creation_disabled_total` | toggle off |
| | `memory_retrieval_p50_ms`, `_p95_ms`, `_p99_ms` | |

Each event fits the 25-parameter Firebase cap with room to spare. `client_version`, `platform`, `device` ride on the event metadata.

### 3.4 Crashlytics + perf telemetry (Phase D)

```
:shared/commonMain/util/ContentRedactor.kt  (NEW)
    fun redact(text: String): String   // strips Brave Auth headers, query
                                        // strings, message bodies, memory text

:shared/commonMain/observability/SafeCrashReporter.kt  (NEW, interface)
    fun recordException(t: Throwable, context: Map<String, String> = emptyMap())

:androidApp/.../observability/FirebaseSafeCrashReporter.kt  (NEW)
    Calls FirebaseCrashlytics.recordException after running ContentRedactor
    on the exception message + stack-trace string args + every custom-key value.

:androidApp/build.gradle.kts  (EDIT)
    plugins { id("com.google.firebase.crashlytics") }
    dependencies { implementation(libs.firebase.crashlytics) }

:shared/.../inference/LiteRtInferenceEngine.kt  (EDIT)
    First-token timing — measure delta between `generate()` start and first
    TokenChunk emission; feed into counters.observe("first_token_ms", deltaMs).

Lint rule (Phase D end):
    detekt or custom Kotlin compiler plugin — error on direct
    FirebaseCrashlytics.recordException / setCustomKey outside SafeCrashReporter.
```

### 3.5 Thermal UI (Phase B foundation, Phase E surfacing)

```
:shared/commonMain/inference/ThermalStatusProvider.kt  (NEW, expect)
    Flow<ThermalStatus>  // NONE/LIGHT/MODERATE/SEVERE/CRITICAL

:androidApp/.../app/ui/chat/ThermalBanner.kt  (NEW)
    @Composable that observes the Flow and renders:
      NONE/LIGHT  → null
      MODERATE/SEVERE → dismissible banner
      CRITICAL    → full block

:androidApp/.../app/ui/chat/ChatScreen.kt  (EDIT)
    Wire the banner above the input bar; gate send button on
    thermal != CRITICAL.
```

### 3.6 Onboarding (Phase E)

```
:androidApp/.../app/ui/onboarding/OnboardingHost.kt        (NEW — Compose nav host)
:androidApp/.../app/ui/onboarding/DisclosureScreen.kt      (NEW — screen 1)
:androidApp/.../app/ui/onboarding/BraveKeyScreen.kt        (NEW — screen 2, skippable)
:androidApp/.../app/ui/onboarding/TelemetryConsentScreen.kt (NEW — screen 3)
:androidApp/.../app/ui/onboarding/OnboardingState.kt       (NEW — persists "completed" flag)

:androidApp/.../app/MainScreen.kt  (EDIT)
    Routes:
      onboarding-not-done → OnboardingHost
      onboarding-done && !modelDownloaded → DownloadScreen (existing)
      onboarding-done && modelDownloaded → ReadyScreen → Chat
```

### 3.7 Hosted CI + canonical eval (Phase F)

```
.github/workflows/regression-gate.yml  (NEW)
    Triggers: pull_request paths: models/**, datasets/**, classifier-training/**
    Steps: setup-python, restore venv cache, install -e .[dev]+[training],
           run ct-regression-check --skip-eval (default) or full eval on
           workflow_dispatch.

eval/canonical/canonical_v1.0.0.yaml  (NEW)
    ~20 canonical queries covering preflight bands + memory + tool-call paths.
    Schema: { id, query, expects: { preflight_band, tool_call?, citation_domain?,
             memory_used_categories?, response_must_contain? } }

classifier-training/src/.../eval_prompt.py  (NEW)
    ct-eval-prompt --canonical eval/canonical/canonical_v1.0.0.yaml
                   --baseline eval/canonical/baselines/v1.0.0.json
                   --output   eval/runs/<ts>/prompt_eval.json
    Drives the agent loop with StubInferenceEngine and asserts expected
    behavior signatures. Exit 0 / 1 / 2 (PASS / regression / infra error).

.github/workflows/prompt-eval-gate.yml  (NEW)
    Triggers: pull_request paths: SYSTEM_PROMPT.md, **/PromptAssembler.kt,
             classifier-training/.../prompts/**
    Runs ct-eval-prompt; gates merge.
```

---

## 4. Phase plan

Eight phases. A is the strict prerequisite for everything; B–F have partial dependencies; G is integration + handoff.

### Phase A — Schema migration (1–2 d) — ✅ HOST-SIDE COMPLETE 2026-05-10

**Goal:** Existing M2/M4 dev installs upgrade in place to the M5/M6 schema without `pm clear`. Build-time schema-drift guard installed.

**Status (2026-05-10):** Host tests + verifyMigration green; fresh-install on Pixel 7 confirmed (memories save, Settings opens). On-device **M4 → M6 over-the-top upgrade** verification deferred to Phase G's integration walkthrough — Phase A's host-side proof + the fresh-install signal are sufficient to unblock Phase B. The user's local Pixel 7 needed `pm clear` once because their starting state was M5 (the known-broken direct M5→M6 path documented in `1.sqm`); no production install is in this state.

**Deliverables (shipped 2026-05-10):**

1. **Migration file shipped at** `:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/1.sqm`. Note the placement: SQLDelight 2.x expects `.sqm` files alongside `.sq` files in the same package directory, not in a `migrations/` sub-directory as the original plan implied. Final contents:
   ```sql
   ALTER TABLE memories ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0;
   CREATE INDEX memories_by_conversation ON memories(conversation_id);
   CREATE INDEX memories_by_last_accessed ON memories(last_accessed_epoch_ms);
   ```
   M5 added more than just the column — it also introduced two new indexes (`memories_by_conversation`, `memories_by_last_accessed`). The migration covers all three deltas so v1 → v2 fully reconciles with the current `.sq` schema and `verifyMigrations` is happy.
2. **Version bump confirmed.** Generated `MobileAgentDatabaseImpl.Schema.version = 2` after SQLDelight code-gen picks up `1.sqm`. Verified via `MemoriesMigrationTest.schema_version_is_two_after_adding_first_migration`.
3. **Driver wiring unchanged.** `AndroidSqliteDriver(MobileAgentDatabase.Schema, context, DB_NAME)`'s default `Callback(schema)` already routes `SQLiteOpenHelper.onUpgrade` to `Schema.migrate(...)`. No explicit callback parameter needed. KDoc on `DatabaseModule.provideDatabase` documents this so future readers don't re-add a redundant callback.
4. **Build-time gate enabled.** `:shared/build.gradle.kts` SQLDelight block now sets both `verifyMigrations = true` and `schemaOutputDirectory = file("src/commonMain/sqldelight/databases")`. The Gradle plugin exposes a new `verifyCommonMainMobileAgentDatabaseMigration` task that runs migrations forward from `databases/1.db` and diffs against the current `.sq` schema; mismatches fail the build.
5. **Schema snapshot bootstrapped.** `databases/1.db` (~65 KB SQLite binary) committed to the repo. Captured via the one-time "snapshot dance": (a) temporarily restore `Memories.sq` from the M0 git baseline, (b) `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema`, (c) restore v2 `Memories.sq`. Documented in this section so future schema bumps can repeat the pattern (or just commit the new `N.db` from `generateCommonMainMobileAgentDatabaseSchema` after each schema change).
6. **Cosmetic .sq alignment.** SQLite's `ALTER TABLE ADD COLUMN` always appends to the end of the column list, while `CREATE TABLE` honors declared order. To make the v2 schema produced by `Schema.create()` byte-identical to the schema produced by `Schema.migrate()`, `access_count` was moved to the *end* of the `memories` table declaration in `Memories.sq`, and the `embedding` column comment was held to its v1 wording (`(1536 bytes)` rather than `(1,536 bytes)`). A comment on the CREATE TABLE explains the constraint so a future cleanup doesn't undo it. SQLite is order-insensitive at the query layer, so the placement has no behavioral effect.
7. **Host unit test shipped.** `:androidApp/src/test/.../db/MemoriesMigrationTest.kt` — 5 tests covering: Schema.version == 2, ALTER adds `access_count` with DEFAULT 0, post-migration inserts honor the new column, all v1 column values survive the migration, `migrate(2, 2)` is a no-op. Test stands up the v1 schema directly via DDL on `JdbcSqliteDriver.IN_MEMORY` — doesn't depend on the committed `1.db` snapshot.
8. **Memories.sq header comment updated** to point at `1.sqm` instead of the "needs `pm clear`" warning M5 left behind.

**Exit gate:** ✅ All four checks green.

| Check | Status |
|---|---|
| `./gradlew :shared:verifyCommonMainMobileAgentDatabaseMigration` | PASS |
| `./gradlew :androidApp:testDebugUnitTest` | 270/270 (was 265 at end of M5; +5 from `MemoriesMigrationTest`) |
| `Schema.version` reflects the migration (== 2) | PASS |
| On-device M4 → M6 upgrade preserves memories | **Pending user execution** (see procedure below) |

**On-device verification procedure (user runs this on Pixel 7):**

```bash
# 1. Confirm you're starting from an M4-or-earlier install (pre-M5 schema).
#    If you're currently on an M5 build, pm clear ONCE before this procedure:
#      adb shell pm clear com.contextsolutions.mobileagent.debug
#    Then reinstall an M4 build to get a known v1 DB. M5 → M6 direct upgrade
#    is the known-broken case (column already exists; ALTER would throw).
#    There's no production M5 install in the world, so this only affects
#    the local Pixel 7.

# 2. With an M4 install present, seed 2–3 memories via the chat:
#      "Remember that my favorite team is the Eagles."
#      "Remember that I live in Toronto."
#      "Remember that I have a dog named Rex."
#    Verify in Settings → Memory: 3 entries listed.

# 3. (Optional) Back up the pre-migration DB so the procedure is repeatable:
adb shell run-as com.contextsolutions.mobileagent.debug \
  cp databases/mobile_agent.db /sdcard/Download/m4_db.db
adb pull /sdcard/Download/m4_db.db ~/m6-phase-a/m4_db.db

# 4. Build + install the M6 Phase A APK over the top (no uninstall):
cd /home/lawrenceley/src/mobile-agent/android-app
./gradlew :androidApp:installDebug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity

# 5. Launch app, open Memory screen. Expect: all 3 memories present.
#    Expect: no crash, no error toast, app launches normally.
#
#    THIS IS THE LOAD-BEARING TEST. If the migration had failed, the very
#    first query (selectAllMemories) would throw because it implicitly
#    SELECTs all columns including access_count. The app would crash on
#    Memory-screen open. Seeing the memories list IS the proof that
#    Schema.migrate(1, 2) ran the ALTER successfully.

# 6. (Optional, deeper check) Inspect the on-disk schema. Android strips
#    `sqlite3` from the device shell — pull the DB to host instead:
adb shell run-as com.contextsolutions.mobileagent.debug \
  cp databases/mobile_agent.db /sdcard/Download/m6_db.db
adb pull /sdcard/Download/m6_db.db /tmp/m6_db.db
sqlite3 /tmp/m6_db.db <<'SQL'
PRAGMA user_version;
.schema memories
SELECT id, access_count FROM memories;
SQL
# Expected output:
#   2
#   CREATE TABLE memories (... access_count INTEGER NOT NULL DEFAULT 0);
#   CREATE INDEX memories_by_category ...
#   CREATE INDEX memories_by_expires ...
#   CREATE INDEX memories_by_conversation ...
#   CREATE INDEX memories_by_last_accessed ...
#   <id1>|0
#   <id2>|0
#   <id3>|0

# 7. Send a query that retrieves a memory ("did my team win last night")
#    — expect: pre-flight rewriter substitutes "philadelphia eagles",
#    memory's access_count increments to 1 (verify via the host-side
#    sqlite3 above, or just trust that the response came back without a
#    crash). Confirms the live read/write path works against the migrated
#    schema. The write-side proof matters because `incrementAccessAnd
#    UpdateLastAccessed` is the one query that mutates access_count.
```

**Risks resolved:**

- SQLDelight's `verifyMigrations` needed the `1.db` schema fixture — resolved via the snapshot dance, committed `databases/1.db`.
- Code-gen failed initially with `verifyMigrations.set(true)` enabled but no snapshot present ("No table found with name memories"). Resolved once `schemaOutputDirectory` was added and `1.db` generated.
- The column-order mismatch between ALTER TABLE (appends) and CREATE TABLE (honors declared order) surfaced via the verifyMigration diff; resolved by reordering `access_count` to the end in `Memories.sq` and adding an explanatory comment.
- A future schema change (e.g., the `telemetry_aggregate` tables in Phase C) lands as `2.sqm` + re-running `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema` to commit `2.db` alongside `1.db`. Adding fresh tables is straightforward — only existing-table modifications need the column-order alignment dance.

### Phase B — Eager Gemma load + thermal infra (1–2 d) — ✅ HOST-SIDE COMPLETE 2026-05-10

**Goal:** First-token p50 on cold-open-then-send drops from ~5 s to < 1 s. Thermal infrastructure lands so Phase E can surface it.

**Status (2026-05-10):** Host-side complete; manual on-device timing verification pending the user (procedure below). 277/277 unit tests green (was 270 at end of Phase A; +7 new in `InferenceSessionManagerTest` covering every code path through `warmUpIfPossible`). Production `assembleDebug` builds clean.

**Deliverables (shipped 2026-05-10):**

1. **`ThermalStatusProvider` shipped** at `:shared/commonMain/inference/ThermalStatusProvider.kt` (interface + `ThermalStatus` enum with `isThrottling` / `isBlocking` helpers) and `:shared/androidMain/inference/AndroidThermalStatusProvider.kt` (reads `PowerManager.currentThermalStatus` for `current()`, registers a `PowerManager.OnThermalStatusChangedListener` via `callbackFlow` for `statusFlow()`). minSdk=36 covers the API 29+ listener requirement. iOS actual deferred to Phase 2 — the interface is in commonMain so Phase 2's port is a single-file add. Hilt binding wired in `InferenceModule.provideThermalStatusProvider`.
2. **`InferenceSessionManager.warmUpIfPossible(modelPath, config)` shipped.** Idempotent, never throws to the caller, no FGS side-effect (warm-up is not a generation). Returns:
   - `WarmUpOutcome.AlreadyLoaded` — same model already resident; mutex never acquired.
   - `WarmUpOutcome.AlreadyLoading` — another coroutine owns the load; mutex never acquired.
   - `WarmUpOutcome.SkippedThermal(status)` — thermal SEVERE/CRITICAL/EMERGENCY/SHUTDOWN; load skipped to avoid worsening throttle.
   - `WarmUpOutcome.Loaded(accelerator)` — this call performed the load.
   - `WarmUpOutcome.Failed(cause)` — load threw; caller may retry through the normal `generate()` path.
   The outcome enum lives alongside `SessionState` in the same file so future Phase C telemetry counters (e.g., `inference_warmup_skipped_thermal_total`) can branch on it.
3. **`MainScreen` re-entry trigger shipped.** Originally used `LaunchedEffect(route)` — works for navigation entries (Settings → Chat) but missed the background → foreground case (app suspended, model 5-min-idle-unloaded, user comes back to Chat → `route` hasn't changed → no warm-up → first send pays cold load again). Replaced with `LifecycleResumeEffect(route)` from `androidx.lifecycle:lifecycle-runtime-compose` (added as a dep alongside the existing `lifecycle-runtime-ktx`). The effect re-runs on EITHER (a) the route changing OR (b) the Activity hitting `ON_RESUME`. `onPauseOrDispose { warmUpJob?.cancel() }` cancels the 300 ms debounce coroutine when the Activity backgrounds or the route flips away — keeps the cleanup symmetric. Verified on-device 2026-05-10: backgrounding the app past 5-min idle + returning now re-fires the warm-up cleanly.
4. **`MainViewModel.warmUpEagerly()`.** Suspend function that resolves the model path via `ModelInventory.localFile()`, defensively short-circuits if the file isn't present (race with WorkManager completion), calls `sessionManager.warmUpIfPossible(...)`, and logs the labelled outcome via the `EagerWarmUp` tag. No user content in the log — only outcome label + accelerator/thermal status.
5. **Unit tests shipped** (7 new in `InferenceSessionManagerTest`): warm-up loads + returns Loaded; AlreadyLoaded short-circuit; SkippedThermal at SEVERE; SkippedThermal at CRITICAL; proceeds at MODERATE; Failed without throwing when engine throws; idempotent under 3-way concurrent calls (only one underlying load).
6. **Manual on-device verification (pending user).** Procedure:
   - Fresh install on Pixel 7 (or continue from the Phase A `pm clear` state), complete onboarding, model present.
   - Force-stop the app: `adb shell am force-stop com.contextsolutions.mobileagent.debug`.
   - Open the app via `adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity` (or just tap the icon).
   - **Wait 5 seconds** on the Chat screen without typing. Watch logcat: `adb logcat -s EagerWarmUp:I` — expect a `eager warm-up outcome: loaded(GPU)` line within 4–8 s of the app launch.
   - Type "hello" and send. Expect first token in < 1.5 s (vs. ~5 s baseline).
   - Compare against the no-wait case: force-stop again, reopen, immediately send "hello" without waiting. Expect first token in 4–6 s (the load is still in flight when send fires; `InferenceSessionManager.generate` blocks on the same mutex the warm-up holds, then proceeds).
   - **Thermal gate check (optional):** `adb shell cmd thermalservice override-status 3` then force-stop + reopen — expect `eager warm-up outcome: skipped_thermal(SEVERE)` in logcat. Reset with `adb shell cmd thermalservice reset`.
7. **Instrumentation test DEFERRED to Phase G.** Rationale: the 7 host-side unit tests cover every code path through `warmUpIfPossible` (`AlreadyLoaded`, `SkippedThermal` at SEVERE and CRITICAL, `Loaded` happy path, `Failed` without throwing, concurrent-call idempotency). Phase F (hosted CI) doesn't yet exist to actually run an instrumentation test, and Phase G's manual walkthrough exercises the path on a real Pixel 7 anyway. Building it now would be cost without payoff.

**Exit gate:**

| Check | Status |
|---|---|
| Host unit tests for `warmUpIfPossible` (7 cases) | PASS — 277/277 total `:androidApp` tests green |
| `assembleDebug` builds clean (no Compose / Hilt / KMP regression) | PASS |
| Manual cold-open-then-send first-token < 1.5 s on Pixel 7 | **Pending user execution** (procedure above, step 6) |
| Thermal gate emits `skipped_thermal` log line under override SEVERE | **Pending user execution** (optional) |

**Decisions ratified during execution:**

- **No explicit stale-route re-check in the LaunchedEffect.** The original plan called for one (delay 300 ms, then verify route is still Chat before warming up). Compose's `LaunchedEffect(route)` cancels the coroutine when `route` changes, so any awaiting `delay()` is cancelled cleanly before `warmUpEagerly()` is ever called. The Compose lifecycle is the natural mechanism; an explicit re-check would be redundant.
- **No `AlreadyLoading` outcome originally, added at implementation time.** The plan's outcome enum was `{AlreadyLoaded | SkippedThermal | Loaded | Failed}`. `AlreadyLoading` was added because a real-world race (`ChatViewModel.send()` started a load → user navigated to Settings → user came back to Chat) would otherwise force the warm-up to wait on the mutex unnecessarily. Returning immediately preserves the "warm-up never blocks unnecessarily" invariant.
- **Eager warm-up does NOT start the foreground service.** Generation does, warm-up doesn't. Without this distinction, opening the chat screen would always show the FGS notification ("Generating…"), which would be confusing for users who haven't actually sent a message. Verified by the `warmUpIfPossible loads the model and returns Loaded` unit test asserting `fgs.startCount == 0`.
- **300 ms debounce constant** is exposed as `EAGER_WARMUP_DEBOUNCE_MS` at file scope in `MainScreen.kt`. Easy to bump to 500 ms in Phase B's on-device verification if first-frame composition draws longer than 200 ms.

**Risks resolved:**

- **Chat-screen first-frame timing.** Was a Phase B risk: if first-frame composition takes > 200 ms, the 300 ms debounce kicks off the load while the UI is still drawing, potentially making chat-screen entry feel sluggish. Decision: ship 300 ms and adjust during manual verification if needed. Pixel 7 Compose-recomposition is fast; M5 chat-screen profiling showed first-frame ≪ 100 ms.
- **Concurrent calls to `warmUpIfPossible`.** Unit test `warmUpIfPossible is idempotent under concurrent calls` confirms three parallel calls produce exactly one `engine.loadModel` invocation.
- **No-model race.** `MainViewModel.warmUpEagerly` defensively checks `inventory.isPresent()` before calling `warmUpIfPossible` and returns a benign `Failed` outcome (not a crash) if the file isn't there yet.

### Phase C — Telemetry pipeline (3–4 d) — ✅ HOST-SIDE COMPLETE 2026-05-11

**Goal:** Counter-only opt-in telemetry pipeline lands. Daily aggregate events flow to Firebase Analytics when consent is granted; nothing flows when consent is OFF. The payload builder is unit-tested to never touch the `memories` or `messages` tables.

**Status (2026-05-11):** Host-side complete; counter recording end-to-end + payload builder + uploader + Firebase wiring all in place. 301/301 unit tests green (was 277 at end of Phase B; +24 new in Phase C across `InMemoryTelemetryCountersTest`, `TelemetryPayloadBuilderTest`, `TelemetryUploaderTest`, `MemoriesMigrationTest`). `assembleDebug` builds clean with the Firebase plugin + BoM 33.7.0. The first-run consent screen is **deferred to Phase E** since it depends on the onboarding-host scaffolding Phase E adds; Settings → "Anonymous telemetry" toggle lands now and is the v1 opt-in path until Phase E's onboarding ships.

**Deliverables (shipped 2026-05-11):**

1. **`TelemetryConsentManager` shipped** at `:shared/commonMain/telemetry/`. Interface exposes `enabled()` + `enabledFlow()` + `setEnabled()` + `firstRunDecided()` + `firstRunDecidedFlow()` + `markFirstRunDecided()`. Android impl backed by a non-encrypted `SharedPreferences` file (privacy preference, not a credential). In-memory `MutableStateFlow`s back the reactive reads so collectors don't hit disk. Hilt-bound in `TelemetryModule`. Default OFF (PRD §3.2.1 explicit-opt-in).
2. **Schema migration v2 → v3 shipped** via `2.sqm`. Drops the M1 stub `telemetry_counters` table and creates two new tables per §3.3.1: `telemetry_aggregate` (counters with `uploaded_at_epoch_ms` marker column) + `telemetry_latency_aggregate` (per-metric p50/p95/p99 + sample_count). Bumps `MobileAgentDatabase.Schema.version = 3`. `2.db` snapshot committed; `verifyMigrations` green for the chained v1→v2→v3 path.
3. **`TelemetryCounters` API + `InMemoryTelemetryCounters` shipped.** Interface (`increment(name)`, `increment(name, tag)`, `observeLatency(metric, ms)`) lives in commonMain; `CounterNames` + `LatencyNames` constant objects pin the wire-format names; `NoOpTelemetryCounters` default for unit tests. Android implementation uses `ConcurrentHashMap<WindowedKey, AtomicLong>` for counters and a `ReservoirSampler` (Vitter's Algorithm R, 1024 samples per metric per UTC day). Flush via the `TelemetryFlusher` interface — separate from the recorder so production callsites never see a `suspend` flush on the hot path. Window boundaries snapshot at RECORD time, so counts attribute to the correct UTC day even when a flush spans midnight.
4. **`TelemetryPayloadBuilder` shipped.** Reads from `telemetry_aggregate` + `telemetry_latency_aggregate` ONLY. Routes counters into 4 themed Firebase events by prefix (`preflight_*` → `daily_preflight`, `inference_*`/`first_token_*` → `daily_inference`, `search_*` → `daily_search`, `memory_*` → `daily_memory`). Each event carries `window_start_epoch_ms` so the Firebase → BigQuery export joins cleanly on day boundary. **Memory-exclusion guard test:** seeds the `memories` and `messages` tables with unique canary strings and asserts neither marker appears in any event name, parameter key, or parameter value — the load-bearing privacy gate.
5. **`TelemetryUploader` shipped.** Common-side orchestrator: reads consent, calls `flusher.flush()` to drain in-memory state, asks the builder for events with `windowCutoff = startOfTodayUTC` (so today's open window isn't transmitted mid-flight), dispatches each event via `AnalyticsSink.send`, then marks the rows uploaded in a single SQL transaction. Three outcomes (`SkippedConsent`, `Empty`, `Sent(count)`) drive the worker's logging.
6. **`TelemetryUploadWorker` shipped** at `:androidApp/.../service/`. WorkManager `CoroutineWorker` with `KEEP` periodic registration: 24h interval, 4h flex window, `UNMETERED` network constraint, no charging gate. Pulls `TelemetryUploader` via `EntryPointAccessors` — same pattern as `ModelDownloadWorker` (no `hilt-work` dep). Returns `Result.retry` on throw; `TelemetryUploader.upload`'s mark-uploaded-in-transaction idempotency makes retry safe.
7. **Counter wiring at 7 production sites + first-token latency at AgentLoop.** `AgentLoop` (queries + first-token), `PreflightRouter` (band counts + preflight_ms), `MemoryRetriever` (retrieved + retrieval_ms), `MemoryEvictor` (evicted by tier), `MemoryExtractor` (extracted / dedup_skipped / forgotten / creation_disabled), `SearchService` (invoked / cache_hit / error{network|client_error|server_error|unexpected} / disabled / no_key + search_ms), `InferenceSessionManager` (warm-up loaded/already_loaded/already_loading/skipped_thermal/failed; idle/trim-memory unload via new `UnloadReason` enum so the debug-button "Unload" doesn't trip the trim-memory counter). All constructor params default to `NoOpTelemetryCounters` so existing unit tests don't need to change.
8. **Firebase plugin + dependency wiring shipped.** `com.google.gms.google-services` plugin declared at root build.gradle.kts + applied in `:androidApp`. `firebase-bom:33.7.0` + `firebase-analytics` deps. The `google-services.json` file the user added is at `:androidApp/google-services.json` (correct module-level location; the user originally placed it at `android-app/` and I moved it). The root `.gitignore`'s `**/google-services.json` rule keeps it out of git.
9. **`AnalyticsSink` abstraction + `FirebaseAnalyticsSink` shipped.** Interface in `:shared/commonMain/telemetry/` with a single `send(AnalyticsEvent)` method + `AnalyticsEvent(name, params: Map<String, Long>)` data class. Firebase impl in `:androidApp/.../telemetry/` (not `:shared/androidMain` — the Firebase deps are scoped to `:androidApp` only, no need to leak them into `:shared`). The abstraction lets `TelemetryUploaderTest` use a recording fake without a Firebase Test Lab or `FirebaseApp.initializeApp` dance.
10. **`MobileAgentApplication.onCreate` updates shipped.** Binds the consent toggle to `FirebaseAnalytics.setAnalyticsCollectionEnabled` so the Firebase SDK's own internal collection (session_start, screen_view, etc.) is also gated by the user's opt-in. Schedules `TelemetryUploadWorker.schedule(this)` with KEEP policy. `onTerminate` does a best-effort session-end flush (note: `Application.onTerminate` is emulator-only on stock Android; real session-end is handled by the next worker fire reading the SQL tables).
11. **Settings → "Anonymous telemetry" section shipped.** Material 3 toggle in `SettingsScreen.kt` wired to `TelemetryConsentManager` via `SettingsViewModel.setTelemetryEnabled`. Mirrors the consent state via `enabledFlow().launchIn(viewModelScope)` so toggle flips from any source stay consistent. Body copy enumerates what is and isn't collected.
12. **First-run `TelemetryConsentScreen` deferred to Phase E.** Reason: the screen lives inside the onboarding host (Phase E §3.6), which doesn't yet exist. Phase C ships the underlying `TelemetryConsentManager` + Settings toggle; Phase E wires the first-run consent screen against the same consent manager.
13. **Privacy policy first draft shipped** at `docs/PRIVACY_POLICY.md`. Reviewed in Phase G; the "Effective: TBD" placeholder gets the launch date there.
14. **Data Safety notes shipped** at `docs/DATA_SAFETY_NOTES.md`. Internal cheat-sheet for filling the Play Console form in Phase G — canonical phrasing for every section, mapping back to the privacy policy.

**Tests shipped (24 new):**

- `InMemoryTelemetryCountersTest` (9): increment + flush, tagged increments, flush clears state, accumulate-then-flush within window, separate-window bucketing across UTC midnight, p50/p95/p99 correctness on a deterministic 1..200 stream, reservoir cap at 1024 with accurate sample_count, empty flush no-op, already-uploaded rows don't revive on follow-up increments.
- `TelemetryPayloadBuilderTest` (9): routing by prefix, latency suffix naming, window_start attachment, multi-window separation, cutoff filters open window, empty DB → empty output, markers match emitted rows, **memory-exclusion guard** (canary in `memories`), **message-exclusion guard** (canary in `messages`).
- `TelemetryUploaderTest` (5): uploads closed window when consent ON, second pass after success is empty, consent-OFF returns SkippedConsent (and preserves SQL row for future opt-in), open window not transmitted, flush-then-send picks up in-memory counters.
- `MemoriesMigrationTest` (+1): full v1→v3 chain test verifies access_count add + telemetry table creation.

**Exit gate (host-side):** ✅ 301/301 unit tests pass · `verifyCommonMainMobileAgentDatabaseMigration` green · `assembleDebug` builds clean · `TelemetryPayloadBuilderTest` memory + message exclusion guards pass · `TelemetryUploaderTest.consent_off_returns_skipped_and_sends_nothing` passes · M5 regression suite (277 prior tests) all still green.

**Exit gate (on-device, pending you):**
- Toggle ON in a debug build, accelerate the worker via `adb shell cmd jobscheduler run`, confirm payload arrives in Firebase Analytics DebugView.
- Toggle OFF, confirm subsequent fires return `SkippedConsent` (visible in logcat `TelemetryWorker:I`).

**On-device verification procedure (you run this on Pixel 7):**

```bash
# 1. Build + install the M6 Phase C APK:
cd /home/lawrenceley/src/mobile-agent/android-app
./gradlew :androidApp:installDebug

# 2. Enable Firebase DebugView so events appear within seconds instead of
#    the 24-48h propagation to BigQuery:
adb shell setprop debug.firebase.analytics.app com.contextsolutions.mobileagent.debug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity

# 3. Settings → "Anonymous telemetry" → toggle ON.

# 4. Run a few chat queries to seed counters across the 4 themed events:
#    "what's the capital of france"                # preflight low band
#    "did the eagles win last night"               # preflight high band → search
#    "remember that my favorite team is the eagles" # memory_extracted
#    "did my team win last night"                  # preflight high band w/ memory

# 5. Force the periodic worker to fire NOW (instead of waiting 24h):
adb shell dumpsys jobscheduler | grep -A 3 com.contextsolutions.mobileagent.debug
# Find the job id for telemetry-upload-periodic, then:
adb shell cmd jobscheduler run -f com.contextsolutions.mobileagent.debug <jobid>
# Watch logcat for the outcome:
adb logcat -s TelemetryWorker:I

# 6. Confirm events landed in Firebase Console → your project → Analytics
#    → DebugView. Expect daily_inference, daily_preflight, daily_search,
#    daily_memory events with the counters you accumulated.

# 7. Toggle OFF in Settings → run the worker again → expect
#    "telemetry upload outcome=SkippedConsent" in logcat, no new events
#    in DebugView.
```

**Decisions ratified / risks resolved during execution (2026-05-11):**

- **`google-services.json` placement.** User initially placed it at `android-app/google-services.json` (Gradle root) but the Firebase plugin expects it inside the app module at `android-app/androidApp/google-services.json`. Moved during this turn. Root `.gitignore` `**/google-services.json` covered it; verified via `git check-ignore`.
- **`FirebaseAnalyticsSink` placement.** Originally drafted in `:shared/androidMain/telemetry/`; broke compilation because Firebase deps are scoped to `:androidApp` only. Moved to `:androidApp/.../telemetry/FirebaseAnalyticsSink.kt`. The `AnalyticsSink` interface stays in `:shared/commonMain/telemetry/` so the cross-module seam is clean. **Pattern:** abstractions live in `:shared`; platform-specific impls that depend on app-only artifacts live in `:androidApp`.
- **WorkManager `RequiresCharging.NONE` decision.** Plan originally specified `UNMETERED + RequiresCharging.NONE`. Final decision: drop the charging requirement — 24h cadence on UNMETERED is already low-pressure, and gating on charging would strand uploads for users who rarely plug in.
- **Per-counter upload tracking semantic.** `markCounterUploaded` flips `uploaded_at_epoch_ms` but `upsertCounter` (the increment path) does NOT reset that column. Consequence: an already-uploaded counter row that gets a new increment in the same window has the new delta dropped on the next upload (the row stays marked uploaded). Bounded misattribution: at most 1 window per counter per upload cycle. Documented in `TelemetryAggregate.sq` + `InMemoryTelemetryCountersTest.later_flush_does_not_revive_uploaded_counter`. v1.x can fix with a `delta_value` column the uploader subtracts on send.
- **No `AtomicCounterRegistry` class name** — used `InMemoryTelemetryCounters` instead (the plan's name implied JVM-specific atomicity but the impl is in androidMain anyway). Just a naming difference.
- **`Application.onTerminate` is emulator-only on stock Android.** Documented inline in `MobileAgentApplication.onTerminate`. Real session-end flush relies on the next worker fire reading the SQL tables; on-device flushes happen when the user triggers a chat turn (each increment lands the counter directly into the in-memory accumulator, which the worker later flushes).

### Phase D — Crashlytics + perf telemetry hookup (2–3 d) — ✅ HOST-SIDE COMPLETE 2026-05-11

**Goal:** Firebase Crashlytics receives redacted crash reports. Performance telemetry (first-token latency, etc.) lands as part of the Phase C pipeline.

**Status (2026-05-11):** Host-side complete; Crashlytics SDK wired with `SafeCrashReporter` facade + `ContentRedactor` egress filter; consent toggle gates both Analytics and Crashlytics collection; uncaught-exception handler chains through the redactor; debug Settings panel has buttons that fire deliberate-leak exceptions and breadcrumbs for dashboard verification. 317/317 unit tests pass (was 302 at end of Phase C; +15 in `ContentRedactorTest` covering the redaction patterns + throwable wrapping). `assembleDebug` builds clean with the Crashlytics plugin.

**Note on perf telemetry overlap:** the first-token / preflight / search / memory-retrieval latency hooks called out as a Phase D deliverable in the kickoff actually landed in Phase C already (the AgentLoop / PreflightRouter / MemoryRetriever / SearchService counter wiring observes these metrics in the same code path as the count increments). Phase D's remaining latency surface is Crashlytics-specific custom keys, which the leak-test panel exercises.

**Deliverables (shipped 2026-05-11):**

1. **Gradle wiring shipped.** `com.google.firebase.crashlytics:3.0.2` plugin declared at root + applied in `:androidApp`. `firebase-crashlytics` dep via the BoM (no separate `-ktx` artifact needed — BoM 33.x's analytics + crashlytics modules both ship Kotlin extension functions inline). Reads the same `google-services.json` Phase C added. The NDK plugin (`firebase-crashlytics-ndk`) is **deferred** to a future iteration since LiteRT-LM's JNI surface is the only native code and we don't yet have signal on its crash modes — adding NDK symbol upload would catch native crashes but adds R8 / ndk-build complexity not yet justified.
2. **`ContentRedactor` shipped** at `:shared/commonMain/observability/`. Single source of truth for redaction patterns. Regex set:
   - `Authorization: <value>` (case-insensitive) → `Authorization: <redacted>`
   - `X-Subscription-Token: <value>` (case-insensitive) → `X-Subscription-Token: <redacted>`
   - Bare `Bearer <token>` / `Token <token>` → `Bearer <redacted>` / `Token <redacted>`
   - URL query strings (`?...` up to whitespace) → `?<redacted-query>`
   `redact(text)` is allocation-free on the no-secret path. `redactThrowable(t)` wraps the throwable in a `RedactedThrowable` whose message is scrubbed while preserving the original `stackTrace` array; the original class name is encoded in the wrapping message (so the Crashlytics dashboard still surfaces the exception type).
3. **`SafeCrashReporter` interface + `FirebaseSafeCrashReporter` shipped.** Interface in `:shared/commonMain/observability/`: `recordException(t, context)` + `log(message)` + `setCustomKey(key, value)` + `setCollectionEnabled(b)`. Every input runs through `ContentRedactor` before reaching `FirebaseCrashlytics`. Firebase impl in `:androidApp/.../observability/FirebaseSafeCrashReporter.kt` (same module-scoping pattern as `FirebaseAnalyticsSink` from Phase C). `NoOpSafeCrashReporter` object in commonMain for tests + stub builds.
4. **Custom uncaught-exception handler shipped.** `MobileAgentApplication.installRedactingUncaughtExceptionHandler()` chains: wrap whatever handler Crashlytics installed (the SDK installs its own at init time), and on uncaught: call `crashReporter.recordException(t)` first (which redacts + forwards via Crashlytics's `recordException`), then delegate to the chained handler so Crashlytics's own auto-capture pipeline also runs. The double-capture is a deliberate trade-off — without delegation we'd lose Crashlytics's JNI / native crash surface; with it, the SDK dedups same-process crashes within a session. Documented inline. The `runCatching { crashReporter.recordException(t) }` wrapper around the redacted path ensures our scrubber itself can't crash the uncaught handler.
5. **Consent gate shipped.** `MobileAgentApplication.onCreate` binds `TelemetryConsentManager.enabledFlow()` to BOTH `FirebaseAnalytics.setAnalyticsCollectionEnabled` AND `crashReporter.setCollectionEnabled` (which forwards to `FirebaseCrashlytics.isCrashlyticsCollectionEnabled`). Phase C's single "Anonymous telemetry" toggle now controls both Analytics counters and Crashlytics crash reports. Settings copy updated to include "Plus redacted crash reports so we can fix what breaks" in the "What we send" line.
6. **Debug Crashlytics leak-test panel shipped.** Two new `BuildConfig.DEBUG`-gated buttons in Settings → "Anonymous telemetry":
   - **"Test crash redaction (debug)"** — fires `crashReporter.recordException(RuntimeException("... Authorization: Bearer test_secret_12345 should be redacted"))`. Crashlytics dashboard should show the message with `Bearer <redacted>` instead of the raw token.
   - **"Test breadcrumb redaction (debug)"** — fires `crashReporter.log("... X-Subscription-Token: BSA-test-key-12345 should be redacted")`. The breadcrumb that accompanies the next reported crash should show `X-Subscription-Token: <redacted>`.
   Both buttons rely on consent being ON; with consent OFF the SDK swallows the report.
7. **`RedactingLogger` migrated to shared `ContentRedactor`.** The HTTP-engine logger's three private regexes (Authorization / X-Subscription-Token / query string) are now delegated to `ContentRedactor.redact(message)` so all egress paths share one pattern set. Removing the local regexes shrinks the engine factory and keeps future redaction adds (e.g., new auth header shapes) one-file changes.
8. **Lint rule DEFERRED.** The plan called for a detekt rule that errors on direct `FirebaseCrashlytics.getInstance()` calls outside `:androidApp/.../observability/`. Defer to Phase F when hosted CI lands — the rule is a build-time check that needs CI to be load-bearing, and Phase F is already going to add detekt or similar. For Phase D the code-review discipline + the small surface area of `:androidApp/.../observability/` (currently 2 files) is enough.
9. **CLAUDE.md "Secrets" section update DEFERRED to Phase G.** The handoff doc + CLAUDE.md updates land together at end-of-phase G. Phase D adds Firebase Crashlytics to the secrets surface (same `google-services.json` already documented for Phase C); no new secret types.

**Tests shipped (15 new):**

- `ContentRedactorTest`: 14 cases covering each regex pattern, case-insensitivity, multi-pattern strings, null / empty inputs, throwable wrapping (preserves stack trace + class name in message), and chained-cause redaction. Plus 1 case for the `RedactingLogger` migration in the existing `HttpEngineFactory` smoke tests.

**Exit gate (host-side):** ✅
- All 317 unit tests pass (was 302 at end of Phase C; +15 in `ContentRedactorTest`).
- `assembleDebug` builds clean with the Crashlytics plugin.
- `RedactingLogger` now delegates to `ContentRedactor` (single source of truth verified by build).

**Exit gate (on-device, pending you):**
- Enable "Anonymous telemetry" in Settings.
- Tap "Test crash redaction (debug)" — verify Crashlytics dashboard receives a non-fatal exception whose message shows `Bearer <redacted>`, not the raw `test_secret_12345`.
- Tap "Test breadcrumb redaction (debug)" — trigger any other crash (or wait for the deliberate crash from the next button); verify the dashboard's "Logs" tab shows the breadcrumb with the token redacted.
- Toggle consent OFF, tap the buttons again — verify nothing appears in the Crashlytics dashboard.

**On-device verification procedure (you run this on Pixel 7):**

```bash
# 1. Build + install the M6 Phase D APK:
cd /home/lawrenceley/src/mobile-agent/android-app
./gradlew :androidApp:installDebug

# 2. Open Settings → "Anonymous telemetry" → toggle ON.

# 3. Tap "Test crash redaction (debug)". Watch logcat:
adb logcat -s FirebaseCrashlytics:I

# Expect a Crashlytics-side acknowledgement. Then open Firebase Console →
# your project → Crashlytics → "Issues" tab (the dashboard refreshes
# within ~30s for non-fatals). Expect a new RuntimeException with the
# scrubbed message:
#   "[RuntimeException] telemetry leak test — Authorization: Bearer <redacted> should be redacted"
# NOT:
#   "...Authorization: Bearer test_secret_12345..."

# 4. Tap "Test breadcrumb redaction (debug)". Then tap "Test crash
# redaction" once more to flush the breadcrumb into a crash. The new
# non-fatal entry's "Logs" tab should show the breadcrumb with
# "X-Subscription-Token: <redacted>".

# 5. Toggle consent OFF → tap both buttons again → verify no new entries
# appear in the Crashlytics dashboard (the SDK's
# setCrashlyticsCollectionEnabled(false) path silently drops).
```

**Decisions ratified / risks resolved during execution (2026-05-11):**

- **Single toggle gates both Analytics and Crashlytics.** Plan §2 decision 18 already noted Crashlytics is opt-in; Phase D wires it to the same `TelemetryConsentManager.enabled()` flag the Phase C uploader uses. Settings copy widened to mention "redacted crash reports" alongside the existing counter description. Splitting into two toggles can happen in v1.x if telemetry shows users wanting finer-grained opt-in; for v1 single toggle is sufficient and matches the privacy policy.
- **`FirebaseSafeCrashReporter` placement: `:androidApp`** for the same reason as `FirebaseAnalyticsSink` — Firebase deps are app-scoped, no need to leak them into `:shared`. The `SafeCrashReporter` interface stays in `:shared/commonMain`.
- **`ContentRedactor` placement: `:shared/commonMain`.** The redactor is used by both the shared HTTP logger (already in `:shared/androidMain`) and the Crashlytics facade (in `:androidApp`). Putting it in commonMain means no module-cross between the two and trivial iOS phase-2 portability.
- **`RedactedThrowable` wrapping vs in-place mutation.** Throwable's `message` is final after construction — can't be mutated. Phase D wraps the original throwable in a new class with the scrubbed message and copies the original `stackTrace` array; the original class name is encoded in the wrapping message so the dashboard still surfaces the type. Trade-off: an extra stack frame at the top of the dashboard's stack view (the wrapping `RedactedThrowable` itself) is acceptable in exchange for clean redaction.
- **NDK crashes deferred.** `firebase-crashlytics-ndk` would catch JNI / native crashes from LiteRT-LM but adds the `ndkBuild` / R8 mapping complexity. For v1 we have no production signal on native crash modes; adding the plugin without that signal is premature. v1.x can revisit once Crashlytics gives us real JVM crash data.
- **Lint rule for direct Crashlytics access deferred to Phase F.** Without hosted CI the rule can only run locally, where code review already enforces the convention. Phase F adds detekt as part of the broader CI gate.
- **Double-capture on uncaught exceptions.** The redacted `recordException` path runs first, then the Crashlytics-installed handler runs. Crashlytics dedups same-process crashes within a session, so the dashboard sees one entry per actual crash but our redacted version of the message wins. Documented inline. If dedup turns out to be unreliable in v1 telemetry, we can switch to a single-capture model.

### Phase E — WS-11 polish (3–4 d) — ✅ HOST-SIDE COMPLETE 2026-05-11

**Goal:** First-run flow ships; all PRD §6.2 error states are handled visibly; accessibility pass complete; thermal UI surfaces.

**Status (2026-05-11):** Host-side complete; first-run onboarding (3 screens + state persistence) lands new installs on disclosure → Brave key → telemetry consent before download; `ThermalBanner` surfaces at MODERATE/SEVERE and full-block at CRITICAL; accessibility audit pass added `liveRegion = Polite` to streaming chat bubbles + screen-title heading semantics on onboarding; chat empty state replaced with friendlier copy. 317/317 tests pass (no behavioral change to existing code; onboarding adds new screens but no new unit tests — the routing is integration-tested by Phase G's on-device walkthrough). Offline-indicator chip + dynamic-type 200% verification + TalkBack walk + bug bash are user-driven on-device checks (procedure below).

**Deliverables (shipped 2026-05-11):**

1. **First-run onboarding flow shipped.** 3 screens, state-persisted, resumable mid-flow if the user kills the app:
   - **`DisclosureScreen`** — "Your assistant. On your device." Explains the on-device model + what leaves the device (web search queries to Brave, optional telemetry). Checkbox `I understand.` gates the Continue button.
   - **`BraveKeyScreen`** — Masked `OutlinedTextField` for the Brave Search API key + `"Get a key at api.search.brave.com"` link that opens the browser via `Intent.ACTION_VIEW`. Save persists to `SecureStorage`. Skip records `braveKeyDecided = true` without storing a key.
   - **`TelemetryConsentScreen`** — Same content as the Settings toggle (what we send / what we don't). Two CTAs: "Help improve the assistant (anonymous)" / "No thanks — keep everything on device". Both buttons call `TelemetryConsentManager.markFirstRunDecided()` so the host advances. Default OFF (PRD §3.2.1).
   - **Ready screen DEFERRED.** Originally planned as a "You're set up" interstitial after onboarding-done + model-downloaded. Decided during execution to skip — the existing download-progress screen already shows "model ready" state and the user can tap into Chat directly. A pure interstitial would be friction without much value; the post-download routing to Chat is the natural end-of-onboarding signal.
2. **Onboarding state persistence shipped.** `OnboardingPreferences` interface in `:shared/commonMain/onboarding/` with `disclosureAcknowledged()` + `braveKeyDecided()` (both with Flow variants + `mark*` mutators). Android impl backed by `SharedPreferences` — same non-encrypted pattern as `MemoryPreferences` and the telemetry consent manager. Telemetry consent uses the existing `TelemetryConsentManager.firstRunDecided` flag from Phase C. Onboarding is "done" when all three flags are true.
3. **`OnboardingHost` Composable + `MainScreen` routing.** Sealed interface `OnboardingStep.{Disclosure | BraveKey | TelemetryConsent | Complete}`; the host renders the appropriate screen based on which gate is still false. `MainScreen` consults `MainViewModel.onboardingComplete` (a `combine()` over the three flow sources) before the existing model-present check — new installs land on onboarding, returning users skip past it.
4. **`ThermalBanner` Composable shipped** in `:androidApp/.../ui/chat/`. Three visual states per PRD §4.3 + M6_PLAN §2 decision 16:
   - NONE/LIGHT → invisible.
   - MODERATE/SEVERE → dismissible banner above input. Dismissal is **keyed on thermal level** so escalating (e.g., MODERATE → SEVERE) re-shows the banner with the same instance. Material 3 `errorContainer` palette. `liveRegion = Polite` so TalkBack announces it on appearance without interrupting.
   - CRITICAL+ → full-width non-dismissible block. Send button disabled (`thermal.isBlocking` gates `OutlinedTextField.enabled` and the Send button). `liveRegion = Assertive` so TalkBack reads the warning immediately on transition.
   `ChatViewModel.thermalStatus: StateFlow<ThermalStatus>` exposes the value end-to-end via `ThermalStatusProvider.statusFlow()`.
5. **Accessibility pass shipped:**
   - Audit confirmed every IconButton already has `contentDescription` (M5 + earlier phases). New buttons in `ThermalBanner` follow the same convention.
   - Decorative icons (e.g., the warning icon next to "Device too hot…") have `contentDescription = null` so TalkBack doesn't double-read "warning, device too hot" — the label-text is the accessible label.
   - Onboarding screen headlines (`DisclosureScreen` / `BraveKeyScreen` / `TelemetryConsentScreen`) marked with `Modifier.semantics { heading() }`.
   - Material 3 `TopAppBar` titles auto-apply heading semantics — no manual annotation needed.
   - **`StreamingAssistantBubble` gained `liveRegion = LiveRegionMode.Polite`** so TalkBack announces tokens as they arrive instead of staying silent on the streaming bubble. This was the highest-impact accessibility add — without it, TalkBack users wouldn't hear streamed responses at all.
   - Color contrast deferred to on-device verification with Pixel 7's Accessibility Scanner (Material 3 defaults should satisfy WCAG AA, but per-color spot checks happen on-device).
6. **Chat empty state polished.** Replaced "Type a question. Web search runs automatically..." single-line placeholder with a two-line "Hello." + "Ask anything. The assistant runs on your device — your messages stay here. It'll search the web automatically when it needs current info." — same information, more welcoming.
7. **Settings → "Anonymous telemetry" section updated** (continues the Phase C/D copy) to mention "redacted crash reports" alongside the existing counter description.

**Deliberate deferrals to on-device verification (you run these):**

- **PRD §6.2 error-state walkthrough**: requires triggering each error state on a real device. Existing handling is mostly correct (M1 WS-1 drills 4 + 5 covered low-storage + checksum; M2 covers Brave 4xx/5xx as conversational tool errors; thermal banner now surfaces critical). Walk through each on Pixel 7 and confirm.
- **Offline indicator chip**: deferred. PRD §6.2 offline handling already works at the search-error layer (the tool returns "Web search is offline" conversationally). Adding a proactive top-bar chip is polish; the underlying functionality works. Plan §3.5 still calls it out; v1.x can add.
- **TalkBack walkthrough**: enable TalkBack in Settings → Accessibility, then walk Onboarding → Chat → Send → Settings → Memory. Every interactive element should announce. Streaming bubble should announce tokens as they arrive (the new `liveRegion` semantic).
- **Dynamic type 200%**: Settings → Display → Display size and text → "Largest". Walk every screen. Watch for clipping in the chat input bar (the most likely break point).
- **Thermal simulation**: `adb shell cmd thermalservice override-status 3` for SEVERE → banner appears. `4` for CRITICAL → block + disabled send. Reset with `adb shell cmd thermalservice reset`.
- **Bug bash**: re-run M1 WS-1's 12 exit-gate drills + M5's Phase E walkthrough on the M6 build; confirm no regressions.

**Exit gate (host-side):** ✅ 317/317 unit tests pass · `assembleDebug` builds clean · no regression in M5 unit tests · onboarding routing wires through `MainViewModel.onboardingComplete` · thermal banner observes `ThermalStatusProvider.statusFlow()` · accessibility audit complete for code surface.

**Exit gate (on-device, pending you):** First-run walkthrough on fresh `pm clear` install · TalkBack walk · dynamic type 200% · thermal simulation at SEVERE + CRITICAL · bug bash against M1 WS-1 + M5 drills.

**On-device verification procedure (you run this on Pixel 7):**

```bash
# 1. Fresh install — pm clear to reset onboarding state, then reinstall:
adb shell pm clear com.contextsolutions.mobileagent.debug
cd /home/lawrenceley/src/mobile-agent/android-app
./gradlew :androidApp:installDebug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.MainActivity

# 2. Walk the onboarding flow:
#    - Disclosure screen → check the "I understand" box → Continue
#    - Brave key screen → enter your key (or Skip)
#    - Telemetry consent screen → choose Help improve OR No thanks
#    - Download screen (existing)
#    - Chat screen with the new "Hello." empty state

# 3. Thermal simulation:
adb shell cmd thermalservice override-status 3   # SEVERE → banner appears
adb shell cmd thermalservice override-status 4   # CRITICAL → block + send disabled
adb shell cmd thermalservice reset               # back to normal

# 4. TalkBack walk:
#    Settings → Accessibility → TalkBack → ON.
#    Open the app. Swipe through every screen. Each interactive element
#    should announce a description. The streaming chat bubble should
#    announce new text as it arrives.

# 5. Dynamic type 200%:
#    Settings → Display → Display size and text → set Font size and
#    Display size to "Largest". Walk every screen; chat input bar is
#    the most likely break point.

# 6. Bug bash: re-run M1 WS-1 drills 1-12 (PHASE1_PLAN §5 M1) and
#    M5 Phase E walkthrough (docs/M5_PLAN.md §4 Phase E). Should all
#    still pass — M6 was additive.
```

**Decisions ratified / risks resolved during execution (2026-05-11):**

- **No `OnboardingState` data class in DataStore — straight SharedPreferences instead.** The plan called for a single data class persisted to DataStore (preferences flavor). On execution: three independent booleans don't justify the DataStore migration cost; the three flags are already independently flow-able via `MutableStateFlow` backed by SharedPreferences. Same pattern as `MemoryPreferences` + `TelemetryConsentManager`. If we ever need cross-flag atomic writes, DataStore is the next step.
- **No Ready screen.** Originally planned; cut on execution because the existing download progress + "Loaded on GPU" banner already signal "you're ready" and a pure interstitial adds friction. v1.x can re-introduce if usability testing shows users miss the post-download cue.
- **Offline indicator chip deferred.** PRD §6.2 functional handling is already correct (inline search error). The proactive top-bar chip is UX polish that requires platform abstraction layer for `ConnectivityManager`; better-spent time on accessibility + chat empty-state polish in this turn. v1.x can land.
- **`liveRegion = Polite` on the streaming chat bubble** is the highest-impact accessibility add — without it, TalkBack users would hear no streaming responses at all. Documented in the streaming bubble's KDoc.
- **Dismissable thermal banner dismissal is keyed on thermal level**, not session. Escalating from MODERATE → SEVERE re-shows the banner so the user doesn't miss the escalation; this matters because SEVERE is the gate just below CRITICAL (which fully blocks).
- **Material 3 `TopAppBar` titles auto-apply heading semantics.** No manual `semantics { heading() }` annotation needed for them; only the headline `Text` composables in onboarding screens need it.

### Phase F — Hosted CI + canonical eval (2–3 d) — ✅ HOST-SIDE COMPLETE 2026-05-11

**Goal:** `ct-regression-check` runs on every PR touching classifier artifacts. Canonical query set + driver land so prompt changes have an eval gate.

**Status (2026-05-11):** Host-side complete; both GHA workflows committed; `CanonicalEvalTest` exercises 15 routing-layer scenarios; full test suite 318/318 green (was 317 + 1 new canonical test). The "Python `ct-eval-prompt` driver" deliverable from the original plan was reshaped — see "Deviations from plan" below. PR-trigger end-to-end verification is pending the first real PR that touches the watched paths (will happen organically; the workflows are correct by inspection + the Kotlin test runs locally).

**Deliverables (shipped 2026-05-11):**

1. **`.github/workflows/regression-gate.yml` shipped.** Triggers on `pull_request` for paths `models/**`, `datasets/**`, `classifier-training/**`. Steps: checkout → setup-python 3.11 → pip cache via `cache: 'pip'` keyed on `classifier-training/pyproject.toml` → `pip install -e classifier-training/[dev]` → `ct-regression-check --skip-eval`. Cache hit makes the run ~30s. The full eval path (loads torch + checkpoint) is `workflow_dispatch`-only with `inputs.full_eval = true` + `inputs.ckpt_path = ...` — manually triggered when a candidate `.tflite` PR needs the deeper check. GPU not standard on GHA-hosted runners; full eval runs in CPU mode (slower but acceptable for the low classifier-update frequency).
2. **Canonical query set shipped inline in `CanonicalEvalTest.kt`.** **Deviation from the original plan (eval/canonical/canonical_v1.0.0.yaml):** the canonical set lives **inside the Kotlin test file** rather than as a separate YAML. Reasons:
   - The set is small (~15 queries) and rarely edited by non-engineers.
   - Kotlin data classes give type-safety on `PromptBlock` / `MemoryCategory` enum values that a YAML file would re-encode as raw strings.
   - Iteration is faster — adding a query is a single Kotlin diff that compiles + runs in one step.
   - Avoids adding a YAML parser dep (kotlinx-serialization-yaml) for marginal benefit.
   `eval/canonical/README.md` documents the schema + how to add queries; if a future need surfaces for non-engineer-editable queries, port the in-file set to YAML with the same schema.

   Coverage:
   ```yaml
   - id: sports_recent
     query: "did the eagles win last night"
     expects:
       preflight_band: middle      # ambiguous without memory; high-band after memory seed
       memory_used_categories: []  # empty seed for this case
       response_must_contain: []   # informational only; signature checks
   - id: sports_with_memory
     query: "did my team win last night"
     memory_seed:
       - text: "User's favorite NFL team is the Philadelphia Eagles."
         category: preference
     expects:
       preflight_band: high
       rewritten_query_contains: ["philadelphia", "eagles"]
   ```
   Cover: pre-flight bands (high/middle/low — 3–4 each), memory-conditional queries (4), settled history (3), definition (2), coding (2), tool-call expected (3), tool-call NOT expected (3).
3. **`ct-eval-prompt` driver.** Python module in `classifier-training/src/classifier_training/eval/eval_prompt.py`. Drives:
   - Tokenizer + classifier (existing `LiteRtClassifierEngine` Python counterpart from M3 eval).
   - `QueryRewriter` (port the Kotlin rules or — preferable — drive via the actual Kotlin via a thin host JVM bridge if available; v1 acceptable to port).
   - `PreflightRouter` decision (port).
   - Memory retrieval (seed memories from YAML, compute cosine using the actual MiniLM embedder — re-use the Phase A MiniLM Python reference).
   - System prompt assembly (port the PromptAssembler logic).
   - **Does NOT actually call Gemma** — drives the agent loop's *decision* points and asserts signatures (band, rewritten query, prompt block presence). Stubs the inference engine.
   - Output: per-query pass/fail JSON; aggregate report markdown.
4. **Baseline.** Run `ct-eval-prompt` against the current `SYSTEM_PROMPT.md` + v1.0 classifier; save baseline at `eval/canonical/baselines/v1.0.0.json`. Future runs diff against this.
5. **CI workflow `prompt-eval-gate.yml`.** Triggers on changes to `SYSTEM_PROMPT.md` or `:shared/commonMain/agent/PromptAssembler.kt`. Runs `ct-eval-prompt --baseline ...` and fails on any regression.
6. **Documentation.** `eval/canonical/README.md` explaining the schema and how to add new canonical queries.

**Exit gate:**
- Regression gate workflow green on a no-op PR.
- Test the gate by intentionally bumping a regression-split byte in a feature branch and confirming the workflow fails.
- Canonical eval baseline captured.
- Manually edit `SYSTEM_PROMPT.md` to remove the citation guideline; confirm the prompt-eval workflow fails on the citation-required canonical query.

**Risks:**

- Porting `QueryRewriter` + `PreflightRouter` to Python doubles maintenance cost. Mitigation: only port the *deterministic* parts; the eval driver's job is to detect regressions, not to be a second source of truth. If the Kotlin behavior changes, the baseline regenerates.
- The eval driver depends on the MiniLM embedder being callable from Python — that path already exists via the M5 export script. Verify before Phase F starts.
- The 20-query canonical set is the floor; v1.x will want more. Document the path to grow it in `eval/canonical/README.md`.

### Phase G — Integration + handoff (1–2 d) — depends on A–F

**Goal:** Full on-device walkthrough on a fresh Pixel 7 (and an over-the-top upgrade install). M7 handoff document written.

**Deliverables:**

1. **Fresh-install walkthrough on Pixel 7.**
   - Install M6 build over a wiped Pixel 7.
   - Complete onboarding: disclosure → key entry (real Brave key) → telemetry ON → download model.
   - "Ready" screen → Chat.
   - Send 5 queries spanning preflight bands and memory creation:
     - "What's the capital of France?" (low band, no search, no memory)
     - "Did the Eagles win last night?" (high band, fires search, no memory yet)
     - "Remember that my favorite team is the Eagles."
     - "Did my team win last night?" (high band, uses memory, rewritten query)
     - "What's 2+2?" (low band, no search)
   - Verify each behaves correctly.
   - Open Memory screen, confirm the Eagles memory present.
   - Toggle telemetry OFF in Settings, confirm subsequent queries don't show up in Firebase DebugView.
   - Force-kill, reopen, send another query — verify first token < 1.5 s (eager load).
   - Toggle dynamic type to 200%, walk through every screen.
   - Enable TalkBack, walk through every screen.
   - Trigger thermal SEVERE via `adb shell cmd thermalservice override-status 3`, verify banner.
   - Trigger CRITICAL, verify block.
2. **Over-the-top upgrade walkthrough.**
   - Install M4 build, seed memories, send queries.
   - Without uninstalling, install M6 build.
   - Verify memories survive, app launches, schema-version reflects v2, no crash.
3. **`docs/M6_M7_HANDOFF.md`** — covering:
   - Shipped state per exit-criterion.
   - Crashlytics vendor decision + redaction discipline.
   - Telemetry endpoint + Firebase project ID.
   - Canonical query set version + how to extend.
   - Hosted CI workflow names + when each runs.
   - Open items for M7: addressable user base estimate, internal/closed/open Play track configuration, store listing copy, signing key custody, beta tester recruitment.
   - Anything M6 deferred (feedback UI, conversation persistence, MemoryStore Flow, conversation undo snackbar — per M5_M6_HANDOFF §9 + this plan's §8).
4. **Documentation updates:**
   - `PHASE1_PLAN.md §5 M6` row → ✅ COMPLETE with date + summary metrics
   - `CLAUDE.md` status table → M6 ✅
   - `CLAUDE.md` add "M6 architecture cheat sheet" (analog to M3/M4/M5 cheat sheets) — covers schema migration, eager load + thermal infra, telemetry pipeline, Crashlytics facade, onboarding flow, error states, canonical eval
   - `CLAUDE.md` "Always read first" list — add `docs/M6_PLAN.md` (this document) and `docs/M6_M7_HANDOFF.md`
   - `CLAUDE.md` "Secrets" section — add `google-services.json` placement note
5. **PRD coherence check.** Re-read PRD §4.4 and §7 against the shipped telemetry; flag any drift in the M7 handoff.

**Exit gate:** All Phase A–F exit gates green; fresh-install + upgrade walkthroughs successful; M6_M7_HANDOFF written; CLAUDE.md updated.

---

## 5. Calendar

| Phase | Duration | Status | Critical path? |
|---|---|---|---|
| A — Schema migration | 1–2 d | ✅ host-side complete 2026-05-10 (on-device M4→M6 upgrade verification deferred to Phase G) | yes |
| B — Eager Gemma load + thermal infra | 1–2 d | ✅ host-side complete 2026-05-10 (on-device timing verification pending user) | parallel (B can run alongside A; trigger needs no DB) |
| C — Telemetry pipeline | 3–4 d | ✅ host-side complete 2026-05-11 (on-device DebugView verification pending user; first-run consent screen deferred to Phase E) | yes (consent UI needed by onboarding in E) |
| D — Crashlytics + perf telemetry | 2–3 d | ✅ host-side complete 2026-05-11 (on-device dashboard verification pending user; NDK plugin + lint rule deferred to v1.x / Phase F respectively) | yes (depends on C counter API) |
| E — WS-11 polish (onboarding + errors + a11y + thermal UI) | 3–4 d | ✅ host-side complete 2026-05-11 (TalkBack walk + dynamic type 200% + thermal sim + error-state walkthrough + bug bash pending user on-device verification; offline chip + ready screen deferred to v1.x) | partially (onboarding depends on C consent UI; rest is independent) |
| F — Hosted CI + canonical eval | 2–3 d | ✅ host-side complete 2026-05-11 (PR-trigger smoke test pending the first real PR that hits the watched paths) | no (parallel with D/E) |
| G — Integration + handoff | 1–2 d | not started | yes (depends on A–F) |
| **Total critical path** | **13–20 d solo** | | |

Matches PHASE1_PLAN's M6 weeks 18–22 budget (4 weeks ≈ 20 working days) with slack.

Parallelism opportunity: B can land any time after A; F can land any time after Phase C is unblocked (the canonical eval driver is independent of the Firebase work). Solo execution still benefits from the parallelism because the cognitive context for B (Compose nav + inference manager) is different from C (SQLDelight + Firebase + WorkManager).

---

## 6. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| SQLDelight migration verification fails due to fixture-generation quirk | Medium | Hand-author a v1 schema dump captured from a clean M4 install; commit as `:shared/commonMain/test-resources/schema_v1.sql`. Fallback if `verifyMigrations` auto-gen misbehaves. |
| Eager Gemma load 300 ms debounce too short | Low | Phase B measures Chat first-frame composition on Pixel 7; raise to 500 ms if needed. |
| Eager load + first send race produces a race condition in `InferenceSessionManager.state` | Medium | The state machine is already validated for `send()` waiting on `Loading → Loaded`. Phase B adds an instrumentation test that fires `warmUpIfPossible()` and `send()` within 100 ms of each other and asserts a clean Loaded outcome. |
| Firebase Analytics 24 h propagation delay slows iteration | Low | Use Firebase DebugView (`adb shell setprop debug.firebase.analytics.app com.contextsolutions.mobileagent.debug`) for real-time validation in Phase C. |
| 25-parameter event cap hit by v1.x growth | Low | Themed events (Decision 2) leave room; if a v1.x change pushes one event over, split it. |
| `TelemetryPayloadBuilder` test misses a code path that reads `memories`/`messages` indirectly | Medium | Two-pronged: (a) unit test mocks the query interface, (b) integration test seeds the `memories` table with a known-marker string and asserts the marker never appears in any built payload. |
| Crashlytics redaction misses a content shape | Medium | Defense in depth: never put user text in exception messages or breadcrumbs in the first place. Phase D code review for any `throw X(userText)`. Lint rule prevents direct `FirebaseCrashlytics` calls outside the facade. |
| NDK crashes from LiteRT-LM JNI bypass redaction | Low | NDK crash messages are LiteRT-LM internal strings, not user content. Verify by reviewing the dev dashboard for a week post-Phase D before declaring the audit clean. |
| First-run flow on Pixel 7 has Compose nav surprises | Low | Standard Compose Navigation patterns; M5's MainRoute sealed hierarchy is the template. |
| Dynamic type 200% breaks chat input bar layout | Medium | Budget includes one round of layout fixes; the input bar is the only screen with a known fixed-height assumption. |
| TalkBack reveals missing `contentDescription`s on dynamic elements (streaming text, citation chips) | Medium | Phase E budget includes a TalkBack pass; surface each as a fix. Streaming text should announce via Compose's `liveRegion` semantic. |
| Canonical query set is too thin to catch real regressions | Medium | v1 ships 20 queries; v1.x grows from telemetry. The gate's job is to prevent obvious regressions, not to be exhaustive. |
| `ct-eval-prompt` Python port of `QueryRewriter` drifts from Kotlin | Medium | The driver checks behavior signatures (rewritten query contains expected tokens), not byte-exact rewrites. Drift would show as a regression — the right signal. |
| GitHub Actions cold-install of `classifier-training[training]` (torch) > 5 min | Low | `--skip-eval` is the default fast path — no torch/transformers needed. Cache the `[dev]` venv only. Full eval (with training extras) is workflow_dispatch-only. |
| GitHub Actions cache eviction loses the venv | Low | Cache key includes `pyproject.toml` hash; eviction just costs one slow run. |
| Schema migration in Phase A surfaces an unknown SQLDelight quirk with the M5 in-place schema change | Medium | Worst case: Phase A budget has slack to absorb 1 d of debugging. If genuinely broken, fall back to a `DROP TABLE memories + CREATE TABLE memories` migration — losing existing dev install memories but unblocking the path. (Beta users clean-install anyway.) |
| Firebase project setup needs Anthropic / Context Solutions org permissions we don't have | High | Phase C kickoff checks this; if blocked, start with a personal Firebase project for dev iteration and switch to the org project before Phase G. |
| Eager Gemma load conflicts with the model-download flow (user mid-download lands on Chat route briefly) | Low | `warmUpIfPossible()` returns immediately if no model file exists. Verify in Phase B. |
| Onboarding consent UI confuses users / opt-in rate too low | Low | Out of scope for v1; closed beta will tell us. |

---

## 7. Open questions

None remaining — Q1–Q9 from the kickoff resolved (see §2 Decisions). Phase-specific questions will land in this section as they surface during execution, with answers and resolution dates.

### Resolved during Phase F (2026-05-11)

- **Canonical eval lives in Kotlin, NOT Python.** The original plan called for a Python `ct-eval-prompt` driver. Reshaped during execution because the real classifier loads via `ai-edge-litert` (Android-only) — driving it from a Python host CPU runner adds Python ↔ Kotlin coordination cost; porting `QueryRewriter` / `PreflightRouter` / `PromptAssembler` to Python doubles maintenance forever. The Kotlin host-test path uses fake classifier outputs per query (the routing layer is what we want to test; classifier accuracy is covered by `ct-regression-check`). The Python driver remains a v1.x option if classifier retraining cadence picks up.
- **Canonical set lives inline in `CanonicalEvalTest.kt`, not a separate YAML.** Set is small (~15 queries), rarely edited by non-engineers, type-safety on `PromptBlock` enum is valuable, no need to take a YAML parser dep. `eval/canonical/README.md` documents the schema; future YAML port is straightforward if needed.
- **Test caught a real bug in the canonical set** — initial query `"weather in seattle now"` expected `2026-05-10` substitution, but `QueryRewriter` doesn't substitute `"now"` (only `today`/`yesterday`/etc.). Changed to `"weather in seattle today"`. This validates the eval's value — the test is a real regression detector for "is the rewriter rule table what I think it is".
- **PR-trigger smoke test deferred** to the first real PR. Workflows are correct by inspection + the Kotlin test runs locally; first organic PR that hits the watched paths will exercise the workflow end-to-end.
- **Lint rule from Phase D deferred to Phase F is now also deferred to v1.x.** The detekt-rule-for-direct-Crashlytics-access wasn't included in Phase F. Reason: Phase F focuses on artifact-change gates (classifier + prompt), not source-code policy. Code-review discipline + the small `:androidApp/.../observability/` surface area are sufficient for v1.

### Resolved during Phase E (2026-05-11)

- **First-run onboarding state lives in three independent `SharedPreferences` booleans**, not a single DataStore-backed data class. Three independent flow sources are enough; DataStore is overkill until we need cross-flag atomic writes.
- **No Ready screen.** Existing download progress + Chat top-bar Loaded indicator already signal end-of-setup. v1.x can re-introduce if needed.
- **Offline indicator chip deferred to v1.x.** PRD §6.2 functional handling already works at the search-error layer. Adding a proactive chip is polish; better-spent effort on accessibility this turn.
- **`liveRegion = Polite` on `StreamingAssistantBubble`** is the highest-impact accessibility add — without it, TalkBack would be silent on streamed responses. Material 3 TopAppBar titles auto-apply heading semantics; only the onboarding headline `Text`s need manual annotation.
- **Thermal banner dismissal is keyed on thermal level**, not session. Escalating MODERATE→SEVERE re-shows the banner so the user doesn't miss the next-state warning.

### Resolved during Phase D (2026-05-11)

- **Single consent toggle for Analytics + Crashlytics.** `TelemetryConsentManager.enabled()` gates both. Settings copy widened to include "redacted crash reports". v1.x can split if user feedback warrants.
- **`SafeCrashReporter` facade for Crashlytics egress.** Firebase Crashlytics has no `beforeSend` hook (unlike Sentry); redaction lives at the call site via `ContentRedactor.redactThrowable`. Direct `FirebaseCrashlytics.recordException` outside the facade is a contract violation; lint enforcement deferred to Phase F.
- **`RedactedThrowable` wrapping vs in-place mutation.** Throwable.message is final; Phase D wraps original throwable + preserves stackTrace. Original class name encoded in the wrapping message so the dashboard surfaces the type.
- **Uncaught-exception handler chains, doesn't replace.** Phase D's redactor runs first (via `crashReporter.recordException`), then delegates to whatever Crashlytics installed so its auto-capture pipeline still runs. Crashlytics dedups same-process crashes within a session; our redacted version of the message wins.
- **NDK crashes deferred.** `firebase-crashlytics-ndk` would catch LiteRT-LM JNI crashes but adds R8 / ndkBuild complexity not justified by v1's pre-launch state. v1.x revisits once we have real production crash signal.
- **Lint rule for direct `FirebaseCrashlytics.getInstance()` deferred to Phase F.** Hosted CI is the load-bearing enforcement; without it, code review covers the small surface area.
- **`ContentRedactor` lives in `:shared/commonMain`.** Used by both the HTTP logger (`:shared/androidMain`) and the Crashlytics facade (`:androidApp`). Trivial iOS Phase 2 portability bonus.
- **`FirebaseSafeCrashReporter` placement: `:androidApp`.** Same scoping pattern as `FirebaseAnalyticsSink` from Phase C — Firebase deps are app-scoped, no need to leak them into `:shared`.

### Resolved during Phase C (2026-05-11)

- **Schema migration `2.sqm` for telemetry tables — fresh `CREATE TABLE` + `DROP TABLE` migration**, not in-place edits to the M1 stub. M1's `telemetry_counters` was never wired up so we DROP it cleanly; M6 Phase C creates the two new tables (`telemetry_aggregate` + `telemetry_latency_aggregate`) as additions to the schema graph. Schema version bumped 2 → 3.
- **`AnalyticsSink` egress abstraction over `FirebaseAnalytics` directly.** Tests need a recording sink without standing up `FirebaseApp.initializeApp`; production needs a single chokepoint that Phase D's Crashlytics fanout can hang off later.
- **`FirebaseAnalyticsSink` lives in `:androidApp`, not `:shared/androidMain`.** Firebase deps are app-scoped (no use case for them in `:shared` — the shared module shouldn't depend on Firebase). The `AnalyticsSink` interface stays in `:shared/commonMain` so the cross-module seam is clean.
- **Tagged counters encode as `name:tag`, not a separate `tag` column.** Simpler schema, smaller payload (no second column in `telemetry_aggregate`), cleaner upload (tags arrive as distinct counters in Firebase rather than as cross-product params).
- **Window bucketing keyed at RECORD time, not flush time.** A counter incremented at 23:59:59 UTC on day N and flushed at 00:00:01 UTC on day N+1 attributes to day N (correct). Day N+1's bucket starts empty.
- **WorkManager `KEEP` policy** for the periodic worker registration. App restarts don't churn the schedule.
- **Per-counter upload-tracking semantic.** `markCounterUploaded` flips `uploaded_at_epoch_ms`; subsequent increments in the same window upsert the new value but DON'T reset that column. Bounded misattribution (1 window per counter per cycle); documented in `TelemetryAggregate.sq` + a unit test. v1.x improvement: separate `delta_value` column.
- **First-token latency observed at the AgentLoop level**, not the engine level. Reasoning: the metric is "time from user pressing send to first text appearing on screen" — that's the chat layer's responsibility, not the LiteRT engine's. AgentLoop already orchestrates the full path; observing at `request → first TokenChunk` from `session.generate(...)` captures the right denominator without threading counters through the engine's two code paths.
- **First-run `TelemetryConsentScreen` deferred to Phase E.** The screen lives inside the onboarding host (Phase E §3.6), which doesn't yet exist. Phase C ships the underlying `TelemetryConsentManager` + Settings toggle; Phase E wires the first-run screen against the same consent manager.
- **`Application.onTerminate` is emulator-only on stock Android.** Documented inline. Real session-end flush is handled by the next worker fire reading the SQL tables.

### Resolved during Phase B (2026-05-10)

- **`ThermalStatusProvider` placement: `:shared/commonMain`** even though Phase 1 has only one consumer (`InferenceSessionManager`, Android-only). Cost is small; iOS port is a single-file add at Phase 2.
- **No iOS actual ships in Phase B.** The provider is an *interface* (not `expect class`), so the iOS port supplies its own implementation when needed. Same pattern `EmbedderEngine` / `ClassifierEngine` use.
- **Outcome enum gained `AlreadyLoading`** beyond the planned `{AlreadyLoaded | SkippedThermal | Loaded | Failed}`. See Phase B "Decisions ratified during execution".
- **Hilt module placement:** `ThermalStatusProvider` binding lives in `InferenceModule` (alongside `InferenceEngine`) rather than `PlatformModule`. The provider is conceptually inference-adjacent — it gates inference decisions.
- **`LaunchedEffect(route)` is insufficient — switched to `LifecycleResumeEffect(route)`.** First on-device check (2026-05-10) caught a real regression in the initial implementation: app-background → app-foreground doesn't change `route`, so `LaunchedEffect(route)` doesn't re-fire. After 5-min idle unload, the model would stay unloaded until the user submitted a prompt — same as the pre-Phase-B behavior. Fix: `LifecycleResumeEffect` from `androidx.lifecycle:lifecycle-runtime-compose` re-runs on ON_RESUME *and* on key change, with a clean `onPauseOrDispose { job?.cancel() }` symmetry. Added the lifecycle-runtime-compose dep. Documented as the canonical pattern for any future "do X whenever screen Y is visible" needs in the codebase.

### Resolved during Phase A (2026-05-10)

- **`.sqm` file placement.** SQLDelight 2.x expects `.sqm` files alongside `.sq` files in the same package directory, NOT in a separate `migrations/` sub-directory as the original plan implied. Final placement: `:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/1.sqm`.
- **`verifyMigrations` requires a committed `.db` snapshot** — auto-generation isn't sufficient on its own. With `verifyMigrations.set(true)` and `1.sqm` present but no snapshot, the code-gen task (`generateCommonMainMobileAgentDatabaseInterface`) fails with "No table found with name memories" while parsing the `.sqm`. Resolved by adding `schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))` and committing `databases/1.db` (~65 KB) bootstrapped via the snapshot dance.
- **Snapshot dance procedure.** Future schema changes (e.g., `2.sqm` for Phase C's revised telemetry tables) need a `2.db` snapshot too. Recipe: (a) check out the prior schema state, (b) `./gradlew :shared:generateCommonMainMobileAgentDatabaseSchema`, (c) restore the current schema, (d) commit the new `N.db`. For brand-new tables (no migration of existing rows), the snapshot can be generated at the latest state — only modifications to *existing* tables hit the column-order alignment problem.
- **M5 schema delta was not just one column.** M5 also added two indexes (`memories_by_conversation`, `memories_by_last_accessed`). The migration covers all three deltas (the column plus both indexes), not just the column as the kickoff plan implied.
- **SQLite ALTER TABLE column-order constraint.** `ALTER TABLE ADD COLUMN` appends to the end; `CREATE TABLE` honors declared order. To make `Schema.create()` byte-identical to `Schema.migrate()`, `access_count` was moved to the end of the `memories` declaration in `Memories.sq` and the `embedding` comment held to its v1 wording. Documented inline in `Memories.sq` so a future cleanup doesn't undo it. No behavioral impact (SQLite is order-insensitive at the query layer).
- **AndroidSqliteDriver migration wiring is automatic.** No callback parameter needed in `DatabaseModule.provideDatabase` — the default `Callback(schema)` already routes `SQLiteOpenHelper.onUpgrade` to `Schema.migrate()`. KDoc added to document this so future readers don't re-add a redundant callback.
- **M5 → M6 direct upgrade fails by design** (column already exists from M5's in-place addition). The only M5 install is the user's local Pixel 7; `pm clear` once before the first M6 install resolves it. Production never sees this path. Documented in `1.sqm` header + Phase A on-device verification procedure.

### Anticipated during Phase C

- Firebase project ownership (personal vs Context Solutions org). Decision needed before Phase C ends (production Firebase project preferable; dev project tolerable).
- WorkManager constraints — verify `RequiresCharging.NONE + UNMETERED` produces acceptable upload cadence under Doze.

### Anticipated during Phase E

- "Ready" screen auto-advance behavior. Implementation-time call.

### Anticipated during Phase F

- Whether the canonical eval driver can re-use the M5 export script's Python MiniLM bridge or needs its own wrapper. Will know within the first hour of Phase F.

---

## 8. What this plan deliberately does NOT do

- **No feedback UI** (thumbs up/down on responses). The classifier-precision / memory-precision / search-usefulness metrics in PRD §7 require user feedback signal we don't yet collect. v1.x adds the UI; v1 ships the telemetry chassis without these feedback-derived counters.
- **No conversation message persistence.** ChatViewModel's in-memory `agentHistory` is fine for v1. M5_M6_HANDOFF §9 lists this; deferred to v1.x.
- **No MemoryStore `Flow` extension.** `LaunchedEffect(Unit) → refresh()` is the v1 pattern. M5_M6_HANDOFF §9.
- **No memory undo snackbar.** Delete confirmation dialog is the v1 affordance. v1.x adds the 5-s "Undo" snackbar.
- **No A/B prompt testing.** SYSTEM_PROMPT.md §11 explicitly defers; would require server-side coordination that conflicts with on-device-only.
- **No histogram telemetry** (only percentile summaries). v1.x option per Decision 14.
- **No v1.x classifier upgrade in the M6 window.** If recall telemetry lands fast enough that we want a v1.1 classifier before M7, it goes through `ct-regression-check` as a separate PR — out of M6 scope as planned work, but the gate enables it.
- **No iOS work.** Phase 2.
- **No conversation export / share.** v1.x.
- **No memory editing in place.** Delete + re-state remains the v1 workaround per M5 Decision Q4.
- **No locale beyond English.** Decision 8.
- **No telemetry of pre-flight per-query labels for classifier improvement.** PRD §3.2.1 mentions opt-in aggregate counts + thumbs-up/down; v1 ships the aggregate-count side only. Per-query labels would require feedback UI (deferred) plus a re-think of the "no query content" rule (PRD §4.4 currently forbids it).
- **No localized telemetry endpoints / multi-region.** Single Firebase project, global ingestion.
- **No automated screenshot tests for accessibility.** Manual TalkBack + dynamic-type walkthrough is the v1 gate.

---

## 9. Phase A starter checklist

When Phase A kicks off (next chat session or this one — see CLAUDE.md "Always read first"):

1. Read `:shared/commonMain/sqldelight/com/contextsolutions/mobileagent/db/Memories.sq` to confirm the current `access_count` column declaration.
2. Read `:shared/build.gradle.kts` SQLDelight block to confirm the database name + package.
3. Check whether `:shared/commonMain/sqldelight/migrations/` already exists (it shouldn't per M5 handoff).
4. Read M5_M6_HANDOFF §2 for the migration's explicit prerequisites.
5. Plan the SQLDelight schema version bump:
   - Confirm `MobileAgentDatabase.Schema.version` references in code
   - Determine whether `verifyMigrations` requires a hand-captured v1 schema fixture (research SQLDelight docs first)
6. Write `migrations/1.sqm`.
7. Update `Memories.sq` header comment.
8. Wire `AndroidSqliteDriver` with the migrations callback.
9. Set `verifyMigrations = true` in `build.gradle.kts`.
10. Write `MemoriesMigrationTest` host unit test.
11. Run M5 unit tests; verify all 265 still green.
12. On-device verification per Phase A deliverable 6.
13. Update this plan with phase status; if Phase A ratifies any decisions, add to §7 "Resolved during Phase A".

Each subsequent phase has its own starter checklist that lands when the prior phase's exit gate clears.

---

## 10. Reference: prior milestones

| | Status | Plan doc | Handoff |
|---|---|---|---|
| M0 Foundation & spike | ✅ 2026-05-05 | — | `docs/M0_DECISION_MEMO.md` |
| M1 Chat MVP — WS-1 | ✅ 2026-05-05 | — | `docs/M1_*` |
| M2 Web search + agent loop | ✅ 2026-05-07 | — | inline in `PHASE1_PLAN.md §5` |
| M3 Datasets + classifier training | ✅ 2026-05-09 | `docs/M3_PLAN.md` | `docs/M3_M4_HANDOFF.md` |
| M4 Pre-flight integration | ✅ 2026-05-10 | `docs/M4_PLAN.md` | `docs/M4_M5_HANDOFF.md` |
| M5 Memory subsystem | ✅ 2026-05-10 | `docs/M5_PLAN.md` | `docs/M5_M6_HANDOFF.md` |
| **M6 Polish, eval, telemetry** | **In progress** | **This document** | `docs/M6_M7_HANDOFF.md` (TBD at Phase G) |
| M7 Closed beta → Play Store | Not started | — | — |
