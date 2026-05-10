# M6 Kickoff Prompt

Use this verbatim in a fresh Claude Code session to start M6 (Polish, eval,
telemetry). The prompt asks Claude to read context, ask clarifying
questions, and produce a phase plan **before** implementing — same shape
that worked for M3, M4, and M5.

---

## The prompt

```
Please create an implementation plan for M6 — Polish, eval, telemetry.
Ask any clarifying questions before implementing.

**Background read first** (CLAUDE.md auto-loads, but read these explicitly):
- `PRD.md` — full v1 spec. Pay special attention to §4.3 (battery / thermal),
  §4.4 (privacy / security), §6.2 (empty states + errors), §7 (success
  metrics — many are telemetry-derived), §8 (open questions, several of
  which M6 finally has the data to answer).
- `PHASE1_PLAN.md` §5 M6 — workstream scope (WS-11 polish, WS-13 telemetry,
  WS-14 hosted CI, WS-15 release engineering, plus the M5 carry-overs and
  the new eager-Gemma-load request listed inline).
- `docs/M5_M6_HANDOFF.md` — operational handoff. §1 specifies the
  telemetry counter-only contract; §2 spells out the schema migration
  story (production-blocker); §3 lists the v1.x classifier-recall queue;
  §4 describes the deferred perf options.
- `docs/M5_PLAN.md` §4 Phase F + §7 (resolved decisions) — covers what
  WS-12 already audited so M6 doesn't redo it.
- `SYSTEM_PROMPT.md` §11 — versioning + canonical query set requirement
  for WS-14.
- `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` §threshold defaults
  + v1.x improvement queue. Some metrics M6 will observe in telemetry
  map directly to model-card weaknesses.

**M5 ship state** (already merged on `main`):

- Memory subsystem fully wired end-to-end: MiniLM embedder
  (`com.google.ai.edge.litert:litert:2.1.4`, 23.5 MB INT8, p95=40 ms),
  SQLite store with brute-force cosine, retrieval injected before
  pre-flight, possessive substitution in `QueryRewriter`, post-turn
  extraction with broadened remember/forget regex, MemoryScreen +
  ConversationMemoryListScreen + chat-bar badge.
- End-to-end retrieval p95 = 72.01 ms on Pixel 7 (PRD §3.2.4 budget
  100 ms ✓). Auxiliary footprint 91.2 MB / 200 MB cap.
- 265 unit tests + 4 instrumentation tests green. Every Phase D/E
  branch covered.
- WS-12 storage-hardening audit done — every `Log.*`/`logger` call
  in the memory pipeline emits counts/IDs/accelerator/`text.length`
  only. Telemetry-exclusion comment markers in `Memories.sq` and
  `MemoryExtractor`.
- Schema migration NOT wired up — `access_count` added in-place;
  existing dev installs need `pm clear` before new builds run.
  (Closed-beta-acceptable; production-blocking.)

**M6 scope per PHASE1_PLAN §5:**

- **WS-11 — UX polish.** First-run flow, accessibility (TalkBack,
  dynamic type, color contrast), all error states from PRD §6.2:
  no Brave key, offline, no model, low storage, thermal critical,
  Brave 4xx/5xx. Thermal warnings in UI per PRD §4.3 (the M0 spike's
  thermal monitoring already has the underlying signal — needs
  surfacing).
- **WS-13 — Opt-in telemetry.** Off by default. **Aggregate counters
  only — no query strings, no memory content, no conversation text.**
  Explicit consent screen with itemized list of what is and is not
  transmitted. Counter shape suggested in `docs/M5_M6_HANDOFF.md` §1.
  Endpoint can be a minimal POST (Cloud Function or similar); cadence
  is daily aggregate roll-up.
- **WS-14 — Hosted CI.** `ct-regression-check` already runs locally
  (M4 Phase E shipped the script). Promote to GitHub Actions or Cloud
  Build so a new classifier `.tflite` can't land without the gate
  passing. Same for the system prompt — canonical query set per
  SYSTEM_PROMPT.md §11.
- **WS-15 — Release engineering.** Crashlytics (or Sentry — open
  question; PHASE1_PLAN §8 #6) with aggressive content scrubbing —
  reuse the redaction pattern from `RedactingLogger`. Performance
  telemetry: model load time, first-token p50/p95, search latency,
  pre-flight hit rate, memory retrieval p95. Data Safety form,
  privacy policy. Internal/closed/open Play track configuration.

**M5 carry-overs (must-do before public launch, per `docs/M5_M6_HANDOFF.md`):**

1. **Schema migration files** for `Memories.sq`. Bump
   `MobileAgentDatabase.Schema.version` to 2 and ship the v1→v2
   `.sqm` capturing the M5 `access_count` addition. Wire
   `verifyMigrations = true` so future drift fails at build time.
   Re-run M5 Phase E walkthrough starting from an M2/M4 install to
   confirm the migration succeeds.
2. **Telemetry contract enforcement.** WS-13's payload builder must
   not read the `memories` table or any memory-tagged logger output.
   Suggested counter shape in handoff §1.
3. **Hosted CI runner** for `ct-regression-check` (above).

**New change request (added during M6 kickoff, 2026-05-10):**

**Eager Gemma load on chat-screen show.** Today the LiteRT-LM Gemma
model cold-loads on the user's first prompt (`InferenceSessionManager`
triggers load when `send()` is called). The 4–8 second cold load is
visible to the user. Move the load earlier — kick it off when the
chat screen first becomes visible — so the model is warm by the time
the user finishes typing.

The existing unload mechanisms stay:
- 5-minute idle timer (M0 Decision 5)
- `onTrimMemory()` proactive unload under system memory pressure

What's open for design (think about and propose in the plan):
- **When exactly to trigger load?** Options: `ChatScreen`'s
  `LaunchedEffect(Unit)` (simplest), Activity `onResume` while the
  current `MainRoute` is `Chat`, or via a new
  `MainScreen`-level `DisposableEffect`. Pick one and document the
  trade-offs.
- **When to unload on hide?** The user's intent: "unload if the
  prompt screen is hidden or the application is in background mode
  and not actively used." Naive interpretation (unload immediately
  on chat-screen leave) thrashes the model when the user briefly
  navigates to Settings or Memory management — those navigations
  are short and the 4–8 second reload would be jarring. Pragmatic
  interpretation: load eagerly on chat-screen show, but rely on the
  existing 5-min idle + `onTrimMemory` for unload. Activity-level
  background-detected via `onPause` with a configurable grace period
  (e.g., 30 s) is a middle ground. Recommend one and call out the
  trade-off.
- **Race against the first user `send()`.** If load is in flight
  when the user submits, the existing path already serializes through
  `InferenceSessionManager.state` (Loading → Loaded), so this should
  Just Work — but verify in the plan that `AgentLoop.run` doesn't
  block on a stale/unloaded handle.
- **Cost when the user opens chat just to read history.** Today the
  load is paid only on send; eager load pays it on every chat-screen
  entry. PRD §4.3 (battery / thermal) should constrain the choice —
  you don't want to cold-load on every momentary chat-screen flip
  if the user habitually flips between memory management and chat.
  Maybe debounce the load by some short window (e.g., 500 ms) so a
  Settings → Chat → Settings flip doesn't trigger a load.
- **Test signal.** M0 / M1 measured cold-load at 4.3 s. Add an
  instrumentation test or manual verification step that confirms the
  user-perceived first-token latency improves after this change
  (target: bring p50 first-token from ~5 s to ~1 s for the
  "open-app-then-immediately-send" flow).

**Things to think through in your plan (in addition to the above):**

1. **Schema migration sequencing.** WS-13 telemetry depends on the DB
   being in a stable state across releases — don't ship telemetry
   before migrations are wired up. Recommend ordering:
   migration → telemetry → Crashlytics → polish.
2. **Telemetry consent UI placement.** First-run vs. settings-only
   vs. both? PRD §3.2.1 says explicit user consent — first-run is
   the obvious moment but adds friction to onboarding.
3. **Crashlytics vendor choice (PHASE1_PLAN §8 #6).** Firebase
   Crashlytics, Sentry, or none? Custom redaction is required either
   way. Open question that M6 finally answers.
4. **WS-14 hosted CI runner.** GitHub Actions self-hosted (we control
   the runner, can pin Python + venv state) vs. GitHub-hosted
   (cleaner but Python venv state has to be fresh each run, ~3 min
   cold install). Cloud Build also viable.
5. **Performance telemetry bucketing.** Histograms vs. just
   p50/p95/p99 sums. PRD §7 lists target metrics; your call on
   distribution shape.
6. **First-run flow scope.** Today MainScreen lands the user on the
   download screen if no model. PRD §6.1 wants a full onboarding:
   on-device disclosure, Brave key affordance, model download size
   acknowledgment. v1 minimum viable vs. polished is a scope question.
7. **Thermal warnings UX.** "We're throttling because the device is
   warm" — surface as a chip, a banner, or silent? PRD §4.3 just
   says "warned" for critical state.
8. **Eager-load × thermal interaction.** If thermal state is SEVERE
   when chat screen opens, do we still trigger the load? Probably
   defer until thermal clears.
9. **Eval harness coverage.** M3's `ct-eval-classifier` covers the
   classifier; the system prompt has no automated eval yet
   (SYSTEM_PROMPT.md §11 talks about a canonical query set but it
   doesn't exist yet). Building it is genuine M6 scope; reasonable
   to defer to v1.x if M6 is tight.

**Don't write code yet** — produce a phase plan with concrete
deliverables per phase, identify the architectural seams, and flag
any open questions that need product/UX answers. Decisions about
Crashlytics vendor, telemetry endpoint hosting, and onboarding scope
are likely product calls that need explicit answers. We'll align on
scope before implementation. Ask whatever clarifying questions you
need.
```

---

## After M6 phase plan is agreed

The same Phase A→F pattern that worked for M3/M4/M5 should apply.
Suggested phase shape (the planner can revise):

- **Phase A** — Schema migration files. Production-blocker; lands first
  so subsequent phases can rely on a stable DB across upgrades.
- **Phase B** — Eager Gemma load + thermal-aware tweaks. Small change,
  user-visible payoff (sub-second first-token on warm path).
- **Phase C** — WS-13 telemetry pipeline. Counter-only, opt-in,
  consent UI. Depends on the redaction pattern from WS-12.
- **Phase D** — WS-15 Crashlytics + content scrubbing + perf telemetry.
- **Phase E** — WS-11 polish (first-run, accessibility, error states,
  thermal UI).
- **Phase F** — WS-14 hosted CI + canonical-query eval harness.
- **Phase G** — Final integration test, on-device walkthrough on
  Pixel 7, M7 handoff at `docs/M6_M7_HANDOFF.md`.

The classifier engine + retrieval path + memory store stay untouched
in M6 — M5 shipped them, M6 only OBSERVES them via telemetry. If a
v1.x classifier upgrade lands during M6 (driven by recall telemetry
showing real numbers), it goes through `ct-regression-check` as the
gate — that's exactly what WS-14 enables.
