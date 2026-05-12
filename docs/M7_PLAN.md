# M7 — Closed Beta → Play Store Launch: Implementation Plan

**Document version:** 0.4 (Draft)
**Status:** Pre-Phase A complete (5 PRs landed); Phase A kickoff pending
**Last updated:** 2026-05-12
**Companion to:** PRD.md §4.4 / §6 / §7, PHASE1_PLAN.md §5 M7, `docs/M6_M7_HANDOFF.md`

---

## 1. Goal

Take the v1.0 build that M6 closed and walk it through Google Play
Console's track progression — **internal → closed → open → production** —
landing on the public Play Store with the addressable Pixel 7 + Android
16 user base. **No new product features.** M7 is release engineering
+ measurement: signing custody, store listing, Data Safety paperwork,
beta-cohort recruitment, crash-rate watching, and staged rollout.

The shipped engineering surface is final at the start of M7. Any
deviation requires explicit re-scoping (see §8).

### Exit criteria (M7 done = v1.0.0 on Play Store production at 100%)

| # | Criterion | Source |
|---|---|---|
| 1 | Release keystore generated, backed up to two custodial locations, Play App Signing enrolled | release hygiene |
| 2 | Play Console app entry created; package name `com.contextsolutions.mobileagent` reserved | Play Console |
| 3 | Privacy policy live at a stable public URL | PRD §4.4 |
| 4 | Data Safety form submitted; reflects the M6 telemetry + Crashlytics contract verbatim | PRD §4.4 |
| 5 | Content rating certificate issued (IARC) | Play Console |
| 6 | App listing complete: short + full description, 5+ screenshots, feature graphic, app icon | Play Console |
| 7 | Internal testing track receives 5–10 internal testers; first signed release APK uploads cleanly | M7 §4 Phase B |
| 8 | Closed testing track receives ≥50 testers; minimum 7-day soak; crash-free session rate ≥99.5% (PRD §7) | PRD §7, §4 Phase C |
| 9 | Open testing track receives ≥1k testers OR closed-beta crash-free ≥99.5% sustained for 14 days waives the open-track gate (per Decision 6) | §4 Phase D |
| 10 | Production launch at staged rollout per Decision 7; 100% promotion gated on 7-day post-50%-rollout crash-free ≥99.5% | §4 Phase E |
| 11 | No P0 bugs open; all P1 bugs from closed beta either fixed in v1.0.x or documented in known-issues for v1.x | bug triage |
| 12 | Final release notes draft; "What's new" string for Play Store | Play Console |

Failure on (1), (3), (4), (5) blocks the internal track itself.
Failure on (8) blocks closed → open promotion. Failure on (10) blocks
production promotion to 100%.

---

## 2. Decisions ratified at planning time

These pin the Phase 1 ship decisions before track-1 work starts. Add
to this list (don't edit it) as M7 surfaces additional choices during
execution.

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | Track sequence | **Internal → Closed → Open → Production**, no skips | Standard Play Store progression. Each track is a measurement window for the next promotion gate. Open testing is the optional widest soak; per Decision 6 it can be waived if closed-beta metrics are strong. |
| 2 | Closed-beta cohort size | **TBD — open question Q1**, default plan: **50–100 testers** | 50 is the lower bound that gives meaningful crash signal; 100 stretches recruitment without much marginal data quality. Q1 surfaces before Phase B. |
| 3 | Privacy policy hosting | **TBD — open question Q2**, default plan: **GitHub Pages on this repo** | Lowest friction: `docs/PRIVACY_POLICY.md` already exists; enabling GH Pages on `main` exposes it at `https://lley154.github.io/mobile-agent/PRIVACY_POLICY.html`. Context Solutions company site is the alternative if a marketing-facing URL is preferred. Phase A locks this. |
| 4 | Release versioning | **`versionName = "1.0.0"`, `versionCode = 1`** for the first internal track build | `versionCode` increments by 1 per Play Store upload regardless of track. Closed-beta builds become 1.0.0+2, 1.0.0+3, etc., via the `versionCode` bump; `versionName` stays "1.0.0" until a real semver change. |
| 5 | Pre-production bug-fix cadence | **One v1.0.x patch per week max during M7** | More than that and we're not measuring; less than that and we're not iterating. Hotfixes outside the cadence allowed for P0 only. |
| 6 | Open testing track | **Optional**. Closed beta with 14 days of sustained crash-free ≥99.5% AND no open P1 bugs may skip directly to production at 1% staged | Open testing is risk reduction; if closed-beta signal is clean enough, the marginal value is low and the launch-day calendar gains a week. |
| 7 | Production staged rollout | **1% → 5% → 25% → 100%**, 48-hour soak at each step before promotion | Standard low-risk staged-rollout cadence. Each step's promotion is gated on crash-free ≥99.5% at that step's user count + no new top-N crash issue surfacing. |
| 8 | Feedback UI (thumbs-up/down) | **Deferred to v1.x** | M6_M7_HANDOFF §8 lists feedback as v1.x. Tempting to pull forward for closed-beta classifier-precision data — but it's a real feature add (~1 week) that delays the closed-beta start. Without it, PRD §7's memory-creation-precision + search-usefulness metrics are estimated from telemetry signals (e.g., search-then-delete-memory ratios) instead of measured. Reassess at end of closed beta. |
| 9 | NDK Crashlytics | **Deferred to v1.x** | Same call as M6 Phase D. JVM crash signal from closed beta is the prerequisite; if a top-N crash points at native code, add NDK in v1.x. v1 has no native code outside LiteRT-LM. |
| 10 | Bug-fix discipline | **No new features, no architecture changes, no dependency bumps unless required for a P0 fix** | M7 is a measurement phase. Stability of the build surface is more valuable than additions. v1.x backlog absorbs every "nice to have". |
| 11 | Crash-free gate threshold | **≥99.5%** per PRD §7, evaluated over the most recent 7-day rolling window per track | Strict gate matches PRD. Lower threshold == ship-it bias; higher threshold == launch-blocked-by-noise risk. |
| 12 | Promotion override path | **Lawrence Ley sole signoff** for v1.0 launch decisions | Solo-engineer project per PHASE1_PLAN §9. No promotion committee; every track promotion is a single explicit decision recorded in this plan's §4 phase log. |
| 13 | Signing keystore custody | **1Password (Context Solutions vault) + sealed offline backup** | Two-custodian rule applied as 1Password + offline. Keystore loss == orphaned app on Play Store, so backups matter more than convenience. |
| 14 | CI signing for release builds | **Not in scope for M7 v1.0 launch** | Solo project + low release cadence means manual signing from a developer workstation is fine. CI signing is a v1.x ops improvement when release frequency increases. |
| 15 | Crashlytics dashboard ownership | **Lawrence Ley** for v1; same Firebase project as Analytics | Single owner, single dashboard. Closed-beta monitoring is daily during Phase C. |
| 16 | "What's new" copy for first listing | **"v1.0 — on-device assistant for Android. Chat, search, and remember — your conversations stay on your device."** | Plain, claim-bounded, matches privacy policy phrasing. Subsequent v1.0.x patches use task-specific copy. |
| 17 | Beta tester recruitment channels | **Context Solutions internal network → personal contacts → r/AndroidAppDev / r/LocalLLaMA opt-in** | No paid recruitment for v1. The communities at the second tier are aligned with the privacy + on-device value proposition. |
| 18 | App icon | **Single launcher icon, no adaptive variants** | v1.0; v1.x can add full adaptive icon set. |
| 19 | Localization | **English only for v1.0** | Already ratified in M6 Decision 8; restated for completeness. |
| 20 | Min/target SDK | **`minSdk=33` (Android 13) per PRD §4.6, `targetSdk` = latest stable Android API at submission time** | Standard pattern. PRD §4.6 says A13 minimum; live device hardware test stays on Pixel 7 + A16. |

---

## 3. Architectural seams

**None — M7 ships v1.0 as M6 closed it.** The only code-side
deliverables are signing config in `:androidApp/build.gradle.kts`,
the `versionCode`/`versionName` bumps per release, and any P0 bug
fixes that surface during closed/open beta. Any structural change
that lands in M7 should be scrutinized against Decision 10's
no-new-features rule.

**Hosted CI scope reminder.** M6 shipped two GHA workflows
(`regression-gate.yml`, `prompt-eval-gate.yml`) that gate
classifier + system-prompt changes on PRs. **Neither gates the
release-track build itself.** Per Decision 14, v1 ships with
manual local signing from a developer workstation, so the CI
pipeline runs as a code-quality gate, not as a release pipeline.
v1.x can add a release-build CI workflow when release cadence
increases. See `docs/M6_M7_HANDOFF.md` §4 for the workflow
contracts.

If closed-beta signal forces a feature-shaped change (e.g., the
Phase G memory-rewrite finding becomes louder than expected and we
decide to ship Gemma-canonical-text in v1.0 rather than v1.x), that
becomes a re-scope decision recorded as a new entry in §2, NOT a
silent expansion of M7.

---

## 4. Phase plan

### Phase 0 — Pre-Phase A landings (shipped) — ✅ COMPLETE 2026-05-12

**Goal:** Five PRs landed ahead of Phase A kickoff. Two are hard
release-blockers (#1 HF auth, #2 main-thread watchdog) — without them
Phase A could not produce a viable `assembleRelease`. The remaining
three (#3 UI polish, #4 memory three-band routing + recall-question
fix, #5 memory export/import) are a mix of polish, ergonomic
improvement, a real bug fix, and one user-portability feature.
Decision 10 (no new features, no architecture changes) interpreted
strictly would have blocked #3 / parts of #4 / all of #5; Lawrence
Ley signed off on each as pre-closed-beta quality work rather than
feature additions, with the explicit understanding that further
scope creep is now off the table and the v1.x backlog absorbs any
remaining polish.

**PRs landed:**

1. **PR #1 — HF auth token BYOK** (merged 2026-05-11, commit `1fcd593`).
   Production users now supply their own HuggingFace API token via a
   new onboarding step (between Brave key and telemetry consent) and a
   Settings section that mirrors the Brave key UX. Wires
   `ModelDownloader` through a new
   `:shared/commonMain/inference/HfAuthTokenProvider` resolver:
   user-supplied token from `SecureStorage` wins; debug builds fall
   back to `BuildConfig.HF_AUTH_TOKEN` from `secrets.properties`;
   release builds always pass `null` for the dev fallback. **Why this
   was a release-blocker:** before PR#1, `release` builds shipped with
   `HF_AUTH_TOKEN = ""` baked into BuildConfig, so the gated Gemma
   download would 401 on every production install. No Play Store
   launch was possible without this. Adds 5 unit tests
   (`DefaultHfAuthTokenProviderTest`).

2. **PR #2 — Main-thread heartbeat watchdog** (merged 2026-05-12, commit
   `c1e2bf4`). Daemon thread pings the main Looper every 5 s; trips
   after 4 missed acks (~20 s, well below `system_server`'s ~60 s
   watchdog). On trip: `InferenceSessionManager.forceUnload(MainThreadWatchdog)`
   + non-fatal recorded via `SafeCrashReporter` + two telemetry counters
   (`main_thread_watchdog_tripped_total`,
   `inference_unloaded_watchdog_total`). Foreground-gated via
   `Application.ActivityLifecycleCallbacks`. Also adds a 60 s post-warmup
   idle unload (existing 5-min post-generation timer unchanged). **Why
   this was a release-blocker:** during PR#1 testing on Pixel 7, Gemma
   running on the Mali-G710 starved the kernel GPU scheduler so badly
   that an unrelated process's main thread parked in uninterruptible
   `D` state for 26 s; ART couldn't suspend it; `system_server`'s own
   watchdog killed `system_server`, and every app on the device got
   `DeadSystemException` (effectively a soft reboot). Without the
   watchdog this would surface in closed beta as anomalous "the phone
   restarted" tester reports with no Crashlytics signal pointing at us.
   Adds 6 unit tests (`MainThreadHeartbeatWatchdogTest`) + 3 new
   `InferenceSessionManagerTest` cases.

3. **PR #3 — UI polish: MD3 light-green theme, dark/light toggle,
   multiline input, copyable bubbles** (merged 2026-05-12, commit
   `834d39e`). Six commits squashed. Re-enables Material You
   `dynamicColor` as the default with a hand-tuned light + dark green
   `ColorScheme` as the brand fallback (Android 11 and below — out of
   scope at minSdk 36 but kept for hygiene — plus users who opt out
   of dynamic colour). New `ThemeMode` (`System` / `Light` / `Dark`)
   persisted via `SharedPreferencesThemePreferences`; chat top bar
   gets a brightness IconButton that cycles the three modes. Fixes
   the onboarding screens' edge-to-edge overlap with the status-bar
   indicators + gesture-nav bar (`safeDrawingPadding()` on the
   Surface-based screens; Scaffold-based screens were already
   insetted). Settings page toggles moved up onto the same row as
   their section headings (Web search + Anonymous telemetry); trimmed
   redundant copy. Chat input: Enter inserts a newline, only Send
   submits (`maxLines = 6` cap). Chat bubbles wrapped in
   `SelectionContainer` so long-press surfaces the system Copy /
   Select all / Share toolbar. Spike menu entry removed from the chat
   top bar; `SpikeActivity` remains launchable via adb. **Decision 10
   exception:** polish + accessibility + UX. Not a feature addition
   in the PRD sense; closer to "build feels rough on first launch,
   beta testers will fixate on it." Lawrence signed off as
   pre-closed-beta-quality work.

4. **PR #4 — Memory three-band routing with user Save/Dismiss prompt
   + recall-question skip** (merged 2026-05-12, commit `2b2e0de`).
   Four commits squashed. Replaces the binary argMax presence cutoff
   with three-band routing mirroring `PreflightRouter`:
   `p_has = softmax(presenceLogits)[HAS_EXTRACTION]` is now compared
   to `auto_save (0.85)` / `ask (0.15)` thresholds defined in a new
   `assets/memory_config.json`. High band → save automatically (as
   before); middle band → inline `MemoryPromptCard` in the chat with
   Save / Dismiss buttons + a new `MemoryPromptCandidate` carrying
   the prebuilt embedding so accept is a single `store.insert`; low
   band → silent skip. Auto-dismiss-previous-on-new-turn rule keeps
   the chat focused on the latest decision. Six new counters route
   to `daily_memory` via the existing `memory_*` prefix
   (`memory_extracted_auto_total` / `_prompted_total`,
   `memory_prompt_shown_total` / `_accepted_total` /
   `_dismissed_total`). **Two robustness fixes shipped in the same
   PR after on-device testing:** (a) argMax category fallback when
   presence has crossed `ask` but no category crosses the sigmoid
   threshold — was silently dropping `p_has=0.93 categories=0` turns;
   (b) `QuestionDetector` runs after `RememberForgetDetector` and
   before the classifier path to skip recall questions — fixes the
   bug where "what is my favorite sports team" was being saved as a
   memory after the assistant echoed the stored fact (classifier was
   trained assuming USER provides the fact; couldn't distinguish a
   recall from a new assertion). Every classifier-path turn now logs
   `presence={skip,skip-question,ask,auto,dedup} p_has=X.XX ask=…
   auto=…` to logcat — IDs and floats only, no memory text per inv.
   #27. Adds `MemoryConfigTest` (4 tests), 8 new `MemoryExtractorTest`
   cases, `QuestionDetectorTest` (7 tests). **Decision 10 exception:**
   this is the closest call of the four. The recall-question skip and
   argMax fallback are bug fixes (Decision 10 explicitly allows P0
   fixes); the three-band routing + user-consent card is a behaviour
   change touching the post-turn pipeline. Lawrence signed off
   weighing closed-beta user-control needs ("the app silently
   memorizes things from low-confidence classifier turns") against
   the no-features rule, with explicit acknowledgement this widens
   scope.

5. **PR #5 — Memory export + import via Storage Access Framework**
   (merged 2026-05-12, commit `3952d1f`). Single squash commit. Adds
   **Export…** and **Import…** entries to the overflow menu on the
   Memory screen (below Clear all). Backup format is JSON text-only —
   the 384-float embedding is regenerated on import via the existing
   MiniLM embedder, so the file is small (~250 B per memory) and
   tolerates an embedder model swap at the cost of a one-shot embed
   pass per row at import time (~40 ms per memory on Pixel 7 CPU).
   New `MemoryExport` / `MemoryExportEntry` data classes + pure
   `MemoryExportSerializer` in `:shared/commonMain`; new
   `MemoryBackupOps` interface + `MemoryBackupController` impl in
   `:androidApp` bridging SAF URIs through `ContentResolver` to the
   serializer + store. `MemoryViewModel` gains a `Channel<BackupEvent>`
   for one-shot toast events + `isBackupBusy: StateFlow<Boolean>`
   for the modal progress overlay. Two new counters
   (`memory_exported_total`, `memory_imported_total`) route to
   `daily_memory` via the existing `memory_*` prefix. Confirm dialog
   warns *"Importing will erase your current N memories and replace
   them with the contents of the chosen file. This cannot be undone."*
   before the SAF picker opens. Schema-version mismatch (file
   `schema_version` > current) is rejected with a "newer version of
   the app" toast, no best-effort migration. Empty-list export
   shortcuts to a "Nothing to export" toast and skips the picker.
   Adds `MemoryExportSerializerTest` (6 tests) + 3 new
   `MemoryViewModelTest` cases (Turbine for `Flow` consumption +
   mockk for `Uri` since host-JVM `android.net.Uri.parse` returns
   null). **Decision 10 exception:** this is a clean feature add
   surfaced as a closed-beta-tester portability concern ("how do I
   move my memories to a new phone before launch"). Lawrence signed
   off explicitly weighing the no-features rule against the closed-
   beta UX. **No further pre-launch additions to be entertained.**

**Combined test movement:** 318 (end of M6) → 332 (after PR #2) →
353 (after PR #4) → **362** (after PR #5). 0 failures throughout.

**Impact on the plan ahead:**
- §6 risk register gains an entry for the GPU-saturation soft reboot,
  now mitigated.
- Phase A §9 starter checklist unchanged — release-signing config wiring,
  keystore generation, store listing, Data Safety, etc. remain
  unaddressed and are still the gate.
- Decision 10 ("no new features, no architecture changes") interpreted
  strictly would have blocked all five PRs to varying degrees. #1 and
  #2 are clear release-blockers (the build was incompatible with
  production without them). #3 is polish surfaced during local
  testing. #4 mixes bug fixes (recall question, argMax fallback) with
  a substantive behaviour change (three-band routing + user-consent
  card). #5 is a clean feature add (memory portability) signed off
  as a closed-beta tester need. Lawrence signed off on each before
  merge with the explicit understanding that further pre-launch
  additions are now off the table — any deferred polish or
  correctness work goes to v1.x.
- The new telemetry counters from #4 (`memory_prompt_*`) and #5
  (`memory_exported_total`, `memory_imported_total`) are added
  signal sources for closed-beta tuning. If telemetry shows users
  dismiss > 70% of middle-band prompts, the `ask` threshold should
  be raised in a v1.0.x patch (Decision 5 cadence allows this).
  Export/import adoption is interesting as a leading indicator for
  "users have something they want to preserve."
- **Data Safety form update needed before Phase A files the
  submission.** PR #5 adds a new explicit data-egress path: memory
  text written to a user-chosen SAF destination. Telemetry counters
  still don't carry text (inv. #27), but the Data Safety form must
  reflect the export-file path as a user-initiated data transfer.
  See `docs/DATA_SAFETY_NOTES.md` — Phase A deliverable §6.

**Exit gate (achieved):**
- All five PRs merged to `main`.
- `./gradlew :androidApp:assembleDebug` clean; **362 unit tests / 0
  failures**.
- Pixel 7 walkthrough confirmed: HF token onboarding lands on the new
  step, 60 s post-warmup idle unload fires, theme toggle cycles
  System → Light → Dark on the chat top bar, multiline input accepts
  Enter as newline, bubble long-press surfaces the system Copy
  toolbar, a high-confidence memory turn auto-saves, a middle-band
  candidate shows the inline card, `adb logcat -s
  MemoryExtractor:I` shows `presence=skip-question` when the user
  asks "what is my favorite sports team" after the fact was saved,
  and Export/Import round-trips via SAF (Files → Drive → Files)
  with the JSON file inspectable on disk.

---

### Phase A — Release plumbing (3–5 d) — depends on M6 complete

**Goal:** Everything Play Console needs in hand before the first
internal track APK uploads. No user-facing work yet — this is the
paperwork + keystore phase.

**Deliverables:**

1. **Release keystore.** Generated via `keytool` on a developer
   workstation. SHA-256 fingerprint recorded in `docs/M7_PLAN.md`
   for verification.
2. **Play App Signing enrollment.** Upload the release keystore to
   Play Console's Play App Signing flow; Google manages the
   distribution signing key thereafter. The original keystore stays
   in custody as the upload key.
3. **Keystore backups.** 1Password (Context Solutions vault) +
   sealed offline backup at a secondary location (per Decision 13).
   Backup procedure documented in `docs/RELEASE_KEYS.md` (new file —
   custodial procedure, NOT the key material itself).
4. **Play Console app entry created.** Reserve
   `com.contextsolutions.mobileagent` package name. Set primary
   contact (Lawrence Ley) + privacy policy URL placeholder.
5. **Privacy policy public URL live.** Per Decision 3, target is
   GitHub Pages on this repo (`https://lley154.github.io/mobile-agent/`).
   `docs/PRIVACY_POLICY.md` → rendered via Pages config; subdomain
   alternative if a marketing-facing URL is required.
6. **Data Safety form submitted.** Filled per
   `docs/DATA_SAFETY_NOTES.md` cheat sheet. Key claims locked:
   "We collect: anonymous usage stats (counters only, opt-in,
   default off)." "We share: nothing." "Brave search queries are
   user-initiated and routed by the app." Refers to the
   public-URL privacy policy.
7. **Content rating certificate issued.** IARC questionnaire — low
   impact (no UGC, no user-to-user chat, no profanity, generative
   AI is disclosed as on-device inference).
8. **On-device LLM disclosure copy drafted.** Used at: (a) the
   Disclosure onboarding screen (already shipped per M6 Phase E),
   (b) the Play Console "AI Disclosures" sub-form, (c) the store
   listing description's third paragraph. Wording is consistent
   across all three surfaces.
9. **Store listing draft.**
   - **Short description (≤80 chars):**
     "On-device AI assistant. Chat, search, and remember — privately."
   - **Full description (~3000 chars):** explains on-device LLM,
     web search via user-supplied Brave key, on-device memory,
     pre-flight classifier, privacy posture. Drafts to live at
     `docs/STORE_LISTING.md` for review before paste-into-console.
   - **Screenshots (5–8):** onboarding disclosure, chat with
     "Loaded on GPU" banner, chat mid-streaming with citation chips,
     memory management screen, settings, optional: thermal banner
     example (only if it doesn't scare users). Pixel 7 portrait only
     for v1.
   - **Feature graphic (1024×500):** designed externally or via
     Figma; v1 can use a simple typographic treatment if a designed
     graphic isn't available by Phase B end.
   - **App icon:** existing launcher icon. Replace if the current
     debug icon isn't shippable.
10. **`:androidApp/build.gradle.kts` release signing config wired.**
    Reads upload-key alias + password from
    `android-app/secrets.properties` (NOT committed; matches
    existing `BRAVE_DEV_KEY` pattern). Release build verifies
    via `./gradlew :androidApp:assembleRelease` locally + `apkanalyzer`
    confirms signed with the upload key.
11. **CI signing secrets** — **NOT in scope for v1** (Decision 14).
    Manual signing from developer workstation for v1.0 + v1.0.x
    releases.
12. **First release APK built (1.0.0+1)** — `assembleRelease` from a
    clean workstation produces the artifact that uploads to internal
    track in Phase B.

**Exit gate:**
- Keystore generated + backed up; SHA-256 verified.
- Play Console app entry visible; package name reserved.
- Privacy policy URL returns 200 over HTTPS.
- Data Safety + Content Rating both show "complete" in Play Console.
- Store listing draft reviewed.
- `./gradlew :androidApp:assembleRelease` produces a valid signed APK.

---

### Phase B — Internal testing track (4–7 d) — depends on A

**Goal:** First release-quality APK on Play Store internal testing
track. 5–10 internal testers exercise the install flow + first-week
crash signal flows to the Firebase Crashlytics dashboard.

**Deliverables:**

1. **Internal track configured** in Play Console. Manual approval
   gating; 5–10 internal testers added by email.
2. **First APK upload** of `1.0.0+1` to internal track. Confirm
   Play Console accepts the upload (signing verified, target SDK
   matches policy, no Pre-Launch Report blocking issues).
3. **Pre-Launch Report review.** Play Console auto-runs the APK on
   Google's device farm + scans for accessibility / security / API
   misuse warnings. Address any blocking warnings before promoting.
4. **Internal-tester install walkthrough.** Each tester installs from
   the Play Store internal track link, walks through onboarding,
   runs ≥5 chat queries (mix of cached/uncached/memory/no-memory),
   reports back via a shared issue tracker (GitHub Issues on this
   repo, label `m7-internal-beta`).
5. **Crashlytics dashboard daily review.** Watch for crash patterns
   over 5+ days. Any P0 (crashes affecting >1% of sessions or any
   crash in the FGS / onboarding / model-download paths) blocks
   closed-beta promotion. Document the 7-day crash-free session
   rate in this section's phase log on promotion day.
6. **Firebase Analytics DebugView spot-check.** Confirm telemetry
   events from real internal-tester devices arrive in DebugView
   (with the `debug.firebase.analytics.app` prop) and in the
   `daily_*` event reports (no debug prop required, 24-48h
   propagation delay).
7. **First feedback iteration.** Any non-P0 bugs surfaced in
   internal beta either (a) get fixed in `1.0.0+2`, (b) get logged
   to v1.x backlog, or (c) get documented as known-issue with a
   workaround.

**Exit gate:**
- Internal track receives APK + at least 5 testers complete onboarding.
- 7-day crash-free session rate ≥99.5%.
- No P0 bugs open.
- Pre-Launch Report shows no blocking warnings.

---

### Phase C — Closed testing (7–14 d) — depends on B

**Goal:** Broader beta soak with 50–100 external testers (per
Decision 2). Generate the first real-world telemetry + crash signal
that PRD §7 metrics measure against.

**Deliverables:**

1. **Closed testing track configured.** Email-list (or Google
   Group) gating. Tester URL distributed through Decision 17's
   recruitment channels.
2. **Tester recruitment.** Target 50–100 active opt-ins by Day 3 of
   Phase C. If recruitment lags, extend Phase C's duration or
   reduce the target (Decision 2 minimum is 50).
3. **Build cadence:** `1.0.0+N` updates per Decision 5 (≤1 per
   week, plus P0 hotfixes ad hoc). Each build's "What's new" copy
   logged in this phase's running notes.
4. **PRD §7 metric measurement window opens.** Watch in BigQuery
   (Firebase Analytics auto-export):
   - Time to first token p50/p90 (PRD target: <1.5s p50 / <2.5s
     p90; revised target per M6_M7_HANDOFF §5 Finding 2: 1–5s
     acceptable, <5s required).
   - Search-tool invocation rate (PRD target: 20–40%).
   - Pre-flight high-band hit rate vs total search-resulting queries
     (PRD target: 70%+ caught by pre-flight).
   - Memory retrieval p95 (PRD target: 100 ms; M5 measurement: 72 ms).
   - Crash-free session rate (PRD target: >99.5%).
5. **Daily crash + ANR review.** Maintain a `docs/M7_CLOSED_BETA_LOG.md`
   summary with the top-N issues per day; flag any spike >1pp
   change in crash rate.
6. **Feedback triage.** Issues from testers labeled
   `m7-closed-beta`. Triaged twice weekly into v1.0.x (in-scope
   for hotfix), v1.x backlog (post-launch), or won't-fix
   (documented).
7. **Closed-beta exit decision.** At Day 7 minimum (Decision 8 in
   PRD §7 implies a 7-day soak baseline), evaluate: crash-free
   ≥99.5% AND no P0/P1 open AND PRD §7 metrics inside acceptable
   ranges. If yes → promote to open testing (Phase D) OR per
   Decision 6 skip directly to production (Phase E) if 14 days
   sustained.
8. **PRD §7 reality check.** Closed-beta data may surface that the
   pre-flight precision gap (M3 §7 GATE FAIL) is louder than
   expected for users, OR the memory-rewrite Finding 1 is more
   frequent than estimated. Document the gap in the closed-beta
   log; decide whether v1.0 ships with documented known limitation
   OR a v1.0.1 hotfix is needed.

**Exit gate:**
- ≥50 active testers for ≥7 days.
- Crash-free ≥99.5% over the last 7 days.
- No P0/P1 bugs open (P2 OK if documented).
- PRD §7 latency + crash metrics inside acceptable ranges.

---

### Phase D — Open testing (5–10 d, OPTIONAL) — depends on C

**Goal:** Broader stress soak before production. **May be skipped**
per Decision 6 if closed beta produced 14 days sustained
crash-free ≥99.5%.

If executed:

**Deliverables:**

1. **Open testing track configured.** Public opt-in URL; no email
   gating. Distributable via the same Decision 17 channels.
2. **Recruitment target: 500–1000 testers** (or natural growth from
   the closed-beta cohort + word of mouth).
3. **Build cadence:** continued `1.0.0+N` updates per Decision 5.
4. **Metric re-validation at scale.** PRD §7 metrics measured
   against a 5–10× larger denominator. Watch for divergence from
   closed-beta values (e.g., latency p95 worsens because broader
   device variance hits Tensor G2 less than Pixel 7).
5. **Pre-launch external review.** Optional: paid 3-day Play Store
   pre-launch consultancy review (UX + privacy posture). Decision
   point in Phase D.

**Exit gate:**
- Crash-free ≥99.5% sustained for the duration of open testing.
- No P0/P1 open.
- Metrics consistent with closed-beta values (no significant
  regression).

---

### Phase E — Production launch (3–7 d active, then ongoing) — depends on C OR D

**Goal:** v1.0 on Play Store production at 100% staged rollout. After
this, M7 transitions to ongoing v1.0.x patching + v1.x roadmap.

**Deliverables:**

1. **Promotion to production at 1%** per Decision 7. APK is the
   same `1.0.0+N` last validated in closed/open beta. 48-hour
   soak.
2. **5% promotion** after 1% crash-free ≥99.5% sustained. 48-hour
   soak.
3. **25% promotion** after 5% sustained. 48-hour soak.
4. **100% promotion** after 25% sustained. End of staged rollout.
5. **Post-launch monitoring window.** First 14 days at 100%:
   daily Crashlytics review; weekly PRD §7 metric snapshot.
6. **Release announcement / launch communication.** Post-launch
   blog or LinkedIn post via Context Solutions channels —
   discretionary, not gating.
7. **v1.0.x hotfix readiness.** Maintain the ability to ship a
   hotfix within 24 hours if a Production-Critical issue surfaces.
   Workstation + signing material accessible; no vacation custody
   gap for the first 14 days.

**Exit gate:**
- 100% staged rollout active for ≥7 days.
- Crash-free ≥99.5% sustained at 100%.
- No P0 bugs open.
- v1.x roadmap kickoff documented (separate v1.x plan doc, not in
  M7 scope).

---

## 5. Calendar

| Phase | Duration | Status | Critical path? |
|---|---|---|---|
| 0 — Pre-Phase A release-blockers | 2 d actual | ✅ complete 2026-05-12 | yes (now resolved) |
| A — Release plumbing | 3–5 d | not started | yes |
| B — Internal testing | 4–7 d | not started | yes |
| C — Closed testing | 7–14 d | not started | yes |
| D — Open testing (optional) | 5–10 d | not started | conditional |
| E — Production launch | 3–7 d active | not started | yes |
| **Total critical path (no skip D)** | **22–43 d solo** | | |
| **Total critical path (skip D per Decision 6)** | **17–33 d solo** | | |

PHASE1_PLAN's M7 budget is weeks 22–26 (4 weeks ≈ 20 working days).
**The lower-bound estimate fits; the upper-bound estimate overruns
by ~3 weeks.** Realistic plan: target the lower-bound for v1.0 launch
calendar, treat upper-bound as a slip indicator.

Slip triggers:
- Phase A > 5 d: Play Console paperwork friction (Data Safety review
  back-and-forth most common).
- Phase B > 7 d: internal-tester recruitment slower than expected OR
  P0 bug surfaces.
- Phase C > 14 d: closed-beta metrics not stabilizing; iterate or
  document and accept.
- Phase D entered: ~1 additional week. Decision 6 escape hatch
  exists.
- Phase E > 7 d active: staged-rollout pauses for crash signal.

Parallelism opportunity: Phase B internal-tester walkthroughs run in
parallel with Phase C closed-beta recruitment + onboarding. Crashlytics
review is a daily 15-minute task throughout C/D/E.

---

## 6. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Classifier presence recall 76.8% silently skips relationship-shaped facts ("my mom's name is X", "I have a sister named Y") | Medium | M6_M7_HANDOFF §6 documented v1.0 weakness. Closed-beta UX impact: users who try to teach personal facts find them missing from the memory list, leading to "the assistant doesn't remember things" perception. Mitigations: (a) onboarding tooltip surfaces the `remember that …` prefix as the reliable path; (b) closed-beta release notes call this out; (c) memory-creation telemetry counters show extracted-vs-attempted ratios — if production data confirms this is louder than expected, pull v1.x dataset expansion (M5_M6_HANDOFF §3) into a v1.0.x patch. |
| Play Console review rejects the Data Safety form | Medium | `docs/DATA_SAFETY_NOTES.md` is the cheat sheet; pre-launch review-style read happens in Phase A before submission. If rejected, revise + resubmit (typically 1-2 day turnaround). |
| Play Console flags the on-device LLM as undisclosed generative AI | Medium | Pre-empt with explicit "AI Disclosures" sub-form filing + matching copy in onboarding + store listing (Phase A §8). |
| Tester recruitment falls short of Decision 2's 50 minimum | Medium | Phase C plan accepts 50 as the floor; if recruitment lags, extend Phase C duration up to 14 days OR open the cohort to wider Decision 17 channels earlier than planned. |
| Crash signal too noisy without NDK Crashlytics; top-N issue traces back to LiteRT-LM JNI | Medium | Defer NDK to v1.x per Decision 9. If a JVM crash signal points at JNI without enough stack info, pull NDK Crashlytics into v1.0.x as a P1 fix. |
| Gemma on Mali-G710 saturates the GPU scheduler enough to trigger a `system_server` soft reboot under load | **Mitigated 2026-05-12** | Surfaced during PR#1 testing on Pixel 7 (cause: kernel GPU scheduler starved unrelated processes' main threads). Mitigated by Phase 0 PR #2 (main-thread heartbeat watchdog) — trips at ~20 s of main-thread stall and pre-empts `system_server`'s ~60 s watchdog by force-unloading Gemma. Also addressed: post-warmup model now releases after 60 s of idle (was: stayed loaded until `onTrimMemory` or process death). Closed-beta evidence will confirm the rate of trips on diverse devices; expected to be ~0 on the Pixel 7 baseline. If trips are nonzero on real users, that's the signal to revisit Gemma's KV-cache GPU memory budget or switch the cache-write path to CPU. |
| Closed-beta surfaces a launch-blocking issue (e.g., memory-rewrite Finding 1 is louder than expected) | Medium | M6_M7_HANDOFF §5 finding 1 is documented as v1.0 known limitation. Closed-beta evidence either confirms this stays acceptable (ship) OR forces re-scope to fix in v1.0 (see §3 architectural-seam note). |
| Brave API cost spikes during open beta or production | Medium | Cache-rate from PRD §3.4 reduces marginal cost; per-user is BYOK (Decision 17 PRD §3.6); dev-built-in Brave key is debug-only. Monitor Brave billing dashboard weekly during Phase C+. |
| Android 16 GA on Pixel 7 delays beyond Phase E start | Low | Resolved in M6 Decision 7 — A16 is GA. Restated here for completeness. |
| Signing keystore lost or compromised | High | Two-custody (Decision 13). Play App Signing means the upload key loss isn't catastrophic (Google manages the distribution key); reset is possible via Play Console. Compromise requires a new upload key + Play Console reset. |
| Crash-free session rate gates promotions indefinitely (closed beta sticks below 99.5%) | Medium | If a single dominant crash issue is the cause, hotfix in `1.0.0+N`. If diffuse / multi-cause, hold + investigate. If 21+ days closed-beta without crossing the gate, escalate to a re-scope decision: lower the gate (PRD waiver), or treat closed-beta as evidence v1.0 isn't ready and slip the launch. |
| Privacy policy URL goes offline mid-launch | Low | GitHub Pages availability is high; the alternative custom domain has hosting SLA. Document URL + monitor uptime via a simple cron-curl check. |
| `assembleRelease` fails on a release-only code path (e.g., R8 / minify regression) | Medium | Phase A §10 includes a release-build smoke test on a clean workstation. Adds a `./gradlew assembleRelease` step to a regular dev workflow so regressions surface during M6/M7 rather than at upload time. |
| Pre-Launch Report (Play Console device farm) surfaces an accessibility regression | Medium | Phase B §3 reviews this before promoting to closed beta. Most TalkBack / dynamic-type 200% gaps were caught in M6 Phase E host-side audit; Pre-Launch Report catches the live-device gaps. Fixes go into the next `1.0.0+N` build. |
| Decision 13's "sole signoff" custody model creates a bus factor | Low | Solo project per PHASE1_PLAN §9. Mitigated by 1Password + sealed offline backup (Decision 13). Production v1.0 launch tolerates this risk; v1.x can introduce a second custodian. |
| Telemetry endpoint Firebase project quotas hit during open testing | Low | Firebase Analytics quotas are generous for ≤1k MAU. Monitor in Phase D; if approaching limits, pause open testing until Firebase quotas raised. |

---

## 7. Open questions

Status as of 2026-05-11 (Phase A pending kickoff):

1. **Closed-beta cohort size**: 50, 100, or somewhere between? Decision 2 default is 50–100. Lock before Phase B.
2. **Privacy policy hosting URL**: GitHub Pages on this repo, or a Context Solutions company-site URL? Decision 3 default is GH Pages. Lock before Phase A end.
3. **Feature graphic source**: design externally (Figma/Fiverr) or self-design a typographic placeholder? Decision in Phase A §9.
4. **App icon**: current launcher icon or new design? Decision in Phase A §9 / §10.
5. **Open testing skip per Decision 6**: confirm acceptance of the 14-day-sustained gate as the skip criterion before Phase D enters.
6. **Beta-tester incentive structure**: zero incentive (current plan), or modest gift card / Brave API credit / etc.? v1 plan is zero.
7. **External pre-launch consultancy review**: $X spend in Phase D, or skip? Decision in Phase D §5.
8. **Production-launch announcement**: blog post / LinkedIn / Hacker News post / silent launch? Phase E §6.
9. **v1.x kickoff timing**: do we wait until v1.0 is at 100% for 14 days before drafting `docs/V1_X_PLAN.md`, or start drafting in parallel during Phase D? Recommend start during Phase D — v1.x backlog is already enumerated in M6_M7_HANDOFF §8.

Phase-specific questions land in this section as they surface during execution, with answers + resolution dates.

---

## 8. What this plan deliberately does NOT do

- **No new product features.** Decision 10. Every v1.x backlog item
  in `docs/M6_M7_HANDOFF.md` §8 stays in v1.x. The closed-beta data
  feeds v1.x prioritization but doesn't trigger in-M7 feature work
  unless an explicit re-scope decision in §2 is recorded.
- **No architecture refactoring.** Same rule — no module
  reorganization, no dependency upgrades except for P0-fix
  necessity.
- **No multi-modal, no iOS, no conversation export, no tablet
  layouts.** PRD §9 deferred-list items stay deferred.
- **No NDK Crashlytics, no detekt lint rules, no feedback UI.**
  Decisions 8, 9; M6 deferrals stay deferred unless closed-beta
  signal forces a re-scope. Specifically: the detekt rule blocking
  direct `FirebaseCrashlytics.getInstance()` calls outside the
  `:androidApp/.../observability/` facade (M6 Phase D §8 / Phase F
  deferral) stays v1.x — code-review discipline + the 2-file
  surface area is sufficient for v1.
- **No CI signing pipeline.** Decision 14. Manual signing for v1.
- **No paid recruitment or paid pre-launch consultancy.** Both are
  open questions (Q7) but the default is no spend.
- **No localization.** Decision 19 / M6 Decision 8. English-only.
- **No new metric instrumentation.** M6 shipped 4 themed events + 7
  counter sites; v1 measures with what's already in the pipeline.
  New counters wait for v1.x.

---

## 9. Phase A starter checklist

Hand-pickable for the first M7 work session. Each item is ≤1 day
solo:

- [ ] Generate release keystore (`keytool`); SHA-256 fingerprint
      recorded.
- [ ] Back up keystore to 1Password vault + offline.
- [ ] Decide privacy policy hosting (Q2) and publish; verify HTTPS
      200 from the public URL.
- [ ] Create Play Console app entry; reserve package name.
- [ ] Fill + submit Data Safety form (per `docs/DATA_SAFETY_NOTES.md`).
- [ ] Fill + submit Content Rating questionnaire (IARC).
- [ ] Submit AI Disclosures sub-form (Play Console).
- [ ] Draft store listing → `docs/STORE_LISTING.md` for review.
- [ ] Capture 5+ screenshots from a Pixel 7 + Android 16 device.
- [ ] Decide feature-graphic source (Q3); produce or commission.
- [ ] Decide app icon (Q4); produce or keep existing.
- [ ] Wire `:androidApp/build.gradle.kts` release signing config.
- [ ] `./gradlew :androidApp:assembleRelease` produces a signed APK;
      `apkanalyzer` confirms signing matches the upload key.
- [ ] Document keystore custody procedure → `docs/RELEASE_KEYS.md`
      (procedure only, no key material).
- [ ] Enroll Play App Signing.
- [ ] Phase A retrospective entry in this doc's §4.A.

---

## 10. Reference: prior milestones

- **M0** — Foundation & spike (`docs/M0_DECISION_MEMO.md`).
- **M1** — Chat MVP (PHASE1_PLAN §5 M1 + 12 WS-1 exit-gate drills).
- **M2** — Web search & agent loop (`PHASE1_PLAN.md` §5 M2).
- **M3** — Datasets & classifier training (`docs/M3_PLAN.md`).
- **M4** — Pre-flight classifier integration (`docs/M4_PLAN.md` +
  `docs/M3_M4_HANDOFF.md`).
- **M5** — Memory subsystem (`docs/M5_PLAN.md` +
  `docs/M4_M5_HANDOFF.md`).
- **M6** — Polish, eval, telemetry (`docs/M6_PLAN.md` +
  `docs/M5_M6_HANDOFF.md`; ✅ complete 2026-05-11).
- **M6 → M7 handoff** — `docs/M6_M7_HANDOFF.md` (release-engineering
  starting point + v1.0 known weaknesses + deferred v1.x items).
- **PRD** — `PRD.md` §4.4 (privacy), §6 (UX), §7 (metrics).
- **PHASE1_PLAN** — `PHASE1_PLAN.md` §5 M7 row + §8 Open questions.

---
