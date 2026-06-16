# M7 Kickoff Prompt

Paste this into a fresh Claude session to start M7 — closed beta → Play
Store launch. M7 is **release engineering only** (no new product
features). The shipped engineering surface is final at the start of M7.

---

## Copy-paste prompt (post-UAT signoff)

```
Please start M7 Phase A — release plumbing. This is the first phase of
the milestone that takes v1.0 from "internal-quality build" (end of M6)
to "v1.0.0 on Play Store production at 100%." No new features.

**Background read first** (CLAUDE.md auto-loads, but read these
explicitly before proposing any plan):

- `docs/M7_PLAN.md` — full M7 phase-by-phase plan + ratified decisions.
  Read §1 goal, §2 decisions (20 items), §4 Phase A deliverables, §7
  open questions (Q1–Q9 — most need answers before Phase A starts), §8
  deliberate non-goals, §9 Phase A starter checklist.
- `docs/M6_M7_HANDOFF.md` — operational handoff from M6. Read §1 ship
  state, §2 telemetry contract (Firebase project = Context Solutions
  production), §3 Crashlytics + redaction discipline, §5 Phase G
  findings (especially Finding 1 memory-rewrite limitation — beta users
  WILL hit this), §6 v1 known weaknesses, §8 open items + v1.x
  deferred list.
- `PHASE1_PLAN.md` §5 M7 row + §6 Pixel 7 specifics + §8 Q1 (Android
  16 GA on Pixel 7 — confirmed GA per M6 Decision 7).
- `PRD.md` §4.4 (privacy invariants — load-bearing for Data Safety
  form) + §6.1 (first-run) + §7 (success metrics; closed-beta gate
  criteria).
- `docs/PRIVACY_POLICY.md` (draft — needs public URL, M7 Phase A §5).
- `docs/DATA_SAFETY_NOTES.md` (Play Console cheat sheet — feeds the
  Data Safety form submission in Phase A §6).
- CLAUDE.md "M6 architecture cheat sheet" + hard invariants 20–28 (M6
  surface area + the Phase G logcat-tag gotcha).

**Decisions to lock with me BEFORE Phase A starts** (these are M7_PLAN
§7 open questions Q1–Q9; pick defaults if you need to):

1. **Q1 — Closed-beta cohort size**: 50 / 75 / 100? Default plan says
   50–100; I'll pick by Phase B start, but Phase A's tester-list
   structure is influenced.
2. **Q2 — Privacy policy hosting**: GitHub Pages on this repo
   (`https://lley154.github.io/local-agent/PRIVACY_POLICY.html`)
   OR Context Solutions company-site URL? Phase A §5 needs this
   locked before publication.
3. **Q3 — Feature graphic source**: self-design (Figma typographic
   placeholder) / commission externally / skip until Phase B? 1024×500
   Play Store banner.
4. **Q4 — App icon**: current launcher icon (probably the debug icon
   right now) or new design? Decision before Phase A §10
   (signing-config wiring assumes the final icon resource is in place).
5. **Q5 — Open testing skip per Decision 6**: accept the 14-day
   sustained crash-free ≥99.5% as the criterion to skip open testing
   entirely and go straight from closed beta to production at 1%?
6. **Q6 — Beta-tester incentive**: zero (current plan) or modest
   (gift card / Brave API credit / etc.)?
7. **Q7 — External pre-launch consultancy review**: spend $X on a
   3-day Play Store pre-launch UX/privacy review during Phase D?
8. **Q8 — Production-launch announcement channel**: silent / blog post
   / LinkedIn / Hacker News? Phase E §6.
9. **Q9 — v1.x kickoff timing**: start drafting `docs/V1_X_PLAN.md`
   during Phase D in parallel, or wait until v1.0 at 100% for 14 days?

**Phase A scope (per M7_PLAN §4 Phase A):**

1. Release keystore generation + custody (1Password + sealed offline
   backup per Decision 13). Document procedure at
   `docs/RELEASE_KEYS.md` (procedure only, NO key material in git).
2. Play App Signing enrollment in Play Console.
3. Play Console app entry; reserve `com.contextsolutions.localagent`
   package name.
4. Privacy policy public URL live (depends on Q2 answer).
5. Data Safety form submission per `docs/DATA_SAFETY_NOTES.md`.
6. Content rating (IARC) submission.
7. AI Disclosures sub-form submission.
8. Store listing draft → `docs/STORE_LISTING.md` for review BEFORE
   pasting into Play Console. Include short description, full
   description, "What's new" copy. Screenshots (5+) from a Pixel 7
   + Android 16 device — capture during this phase.
9. Feature graphic (depends on Q3 answer).
10. `:androidApp/build.gradle.kts` release signing config — reads
    upload-key alias + password from `android-app/secrets.properties`
    (NOT committed; pattern matches the existing `BRAVE_DEV_KEY`).
11. First release APK built: `./gradlew :androidApp:assembleRelease`
    produces a clean signed artifact ready for upload to internal
    track in Phase B. Verify with `apkanalyzer`.
12. Phase A retrospective entry appended to `docs/M7_PLAN.md` §4
    Phase A section ("Decisions ratified during execution" +
    "Risks resolved" + "Exit gate ✅").

**Things to think through in your plan (before doing any work):**

1. **Order of Phase A items.** Some have dependencies: privacy policy
   URL (5) must exist before Data Safety form (6) is submitted. Play
   Console app entry (3) must exist before any sub-form (5, 6, 7)
   filing. Keystore (1) must exist before signing config (10) which
   must exist before assembleRelease (11).
2. **Risk-of-overrun items.** Privacy policy public URL provisioning
   could take a half day if GH Pages config surprises us. Data Safety
   form has a known back-and-forth pattern with Play Console review
   — budget 1–2 days for round-trips. Feature graphic could outsource
   timeline drift here.
3. **What's safe to delegate to me vs what needs your hands.** Keystore
   generation + custody is your call (key material isn't going through
   me). Play Console clicks, IARC questionnaire, Data Safety form
   submission, content rating — those are your hands on Play Console.
   I can orchestrate the procedure + draft copy + diff-review the
   forms before submission.
4. **What gets committed and when.** v1 plan is one commit per major
   Phase A deliverable batch — e.g., one for `docs/STORE_LISTING.md`
   + `docs/RELEASE_KEYS.md`, one for the signing config + first
   release APK build verification. M7 doesn't follow the "one big
   milestone commit" pattern M3–M6 used; release engineering benefits
   from finer commit granularity.

**Don't write code unless required.** Phase A is paperwork + signing
config + one signed APK. The only code-side change is the release
signing block in `:androidApp/build.gradle.kts` and the
`secrets.properties` template update. Everything else is docs +
Play Console.

After Phase A is fully closed, Phase B (internal testing track)
begins. We'll spin out a similar kickoff prompt at Phase A end.
```

---

## Notes for the human running this

- **UAT signoff first.** This prompt assumes M6 has been UAT-signed-off
  and the production build is stable. If you're still iterating on M6
  itself (P1 bugs from the Phase G walkthrough that didn't make it
  into the M6 commit), close those out first; M7 starts from a stable
  M6 baseline.
- **Q1–Q9 don't all need to be answered before pasting the prompt.** A
  fresh Claude will ask you about the open ones and propose defaults.
  But the more you've locked in advance, the faster Phase A moves.
- **Q2 (privacy policy hosting) is the most time-sensitive open item.**
  Decision there gates Phase A §5 which gates Phase A §6 which gates
  pretty much everything else. If you have a preference, lock it
  before kickoff.
- **The `docs/M7_PLAN.md` is the source of truth.** The kickoff prompt
  above is a launch script; the plan is the substance.
- **Iteration before kickoff.** This kickoff prompt is a draft; revise
  it as you iterate on the plan + handoff. Pin a final version once
  you've signed off on the upstream M6 ship state.
