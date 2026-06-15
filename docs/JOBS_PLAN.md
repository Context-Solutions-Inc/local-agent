# Jobs — design plan

Status: **implemented in PR #70** (branch `feat/jobs`). The original design is
preserved below as the design record; the **§0 As-built** section captures where
the shipped feature deviates from it (the job model and a few UI/sync details
changed during implementation — those changes, not the original wording, are
authoritative).

Tracking: see `PHASE1_PLAN.md` / CLAUDE.md for where this slots in. Numbered
"invariants" referenced below are CLAUDE.md hard invariants.

## 0. As-built (PR #70) — deviations from the design below

- **A job is `command` + `prompt`, always a subprocess** (not the COMMAND-vs-
  AGENT_PROMPT split of §1). The desktop runs `command` with `prompt` passed as a
  bound positional shell argument (`sh -c '<cmd> "$1"' sh "<prompt>"` — injection
  safe; PowerShell on Windows). There is **no `TaskQueue`/AGENT_PROMPT path** —
  `JobExecutor` always spawns a process.
- **Each run continues the job's single conversation thread.** On fire,
  `JobExecutor` reuses the job's existing conversation (`last_run_conversation_id`)
  if present, else creates one, appending `user = job name` (PR #84 — **not** the
  `prompt`/"Command Argument", which is the command's input, not chat text) then
  `assistant = response` (`renderMarkdown = true` since PR #82). The conversation
  syncs through the normal `ConversationSyncRecord` path, so **"View conversation"**
  on either platform opens it in Chat via `ChatViewModel.loadConversation` (the
  conversation-history resume flow). The job row denormalises the latest run
  (`last_run_status/at/summary` + `last_run_conversation_id`); **run start writes
  `last_run_status = RUNNING`** to that synced row so both platforms show a working
  spinner mid-run, and the terminal `recordLastRun` overwrites it (PR #84).
- **`job_runs` is desktop-local history (NOT synced); only the `jobs` row syncs**
  (LWW + tombstone), carrying the denormalised last-run so mobile renders "last
  result" and the conversation link with no second synced record type.
- **Trust boundary = an injected `JobSyncPolicy`** (commonMain), not an inline flag
  in `applyFromPeer`. `DesktopJobSyncPolicy` fails **closed** — drops remote inserts
  + remote tombstones, applies only a `paused` toggle to an existing row;
  `MobileJobSyncPolicy` applies authoritative desktop records verbatim. A
  desktop-only `onJobPausedFromPeer` seam on `SqlDelightLinkSyncService` re-drives
  `DesktopJobScheduler` after a peer pause (the raw `updatePausedFromPeer` write
  fires no `LocalChangeBus`).
- **Mobile run-now does NOT ride sync** (PR #84) — running a job spawns an OS
  subprocess, which only ever happens on the trusted desktop, so the phone sends an
  imperative **`RUN_JOB` `LinkMethod`** over the relay (job id in `query["id"]`):
  `RemoteJobRunner`/`RelayRemoteJobRunner` (commonMain seam, **bound mobile-only**) →
  `DesktopLinkRequestHandler.runJob` seam (guards on `JobRepository.get(id)`) →
  `JobService.runNow`. Online-only (needs the link UP). Create/edit/delete remain
  desktop-only; the sync trust boundary above is unchanged (run-now is a separate
  imperative channel, not a synced column).
- **`SqlDelightJobRepository.flow()` MUST be a reactive SQLDelight query**
  (`selectAllJobs().asFlow().mapToList`). Synced rows are written by the sync layer
  via the raw `*FromPeer` queries, **bypassing the repo**, so a manually-seeded
  `StateFlow` (the `tasks`/`todos` pattern) goes stale and synced jobs never appear
  on the phone until an app restart. The reactive query repaints on any write to the
  `jobs` table on that driver (both directions).
- **Mobile Jobs icon shows only when paired** (Material Symbol `rule_settings`, a
  hand-built `ImageVector` since the pinned material-icons-extended predates it).
  Originally (PR #70) it was always shown; **PR #80** gates it on
  `isDesktopPlatform || DesktopLinkStatus != DISABLED` — there's no point offering it
  on a phone with no desktop to run jobs. Once paired it **stays visible even when the
  relay is offline** so it opens the last-synced list. A header shows **"Synced Nm
  ago • Offline"** (persisted `LastSyncStore` + reactive `MutableLastSyncStatus`, set
  when `SyncController` reaches the peer). Pause/resume is **view-only offline**
  (enabled only when the desktop controls: `isAdmin || link UP`) **and** only while
  the job has future runs.
- **Control gating** (`hasFutureRuns`, keyed on `fireAt > now` for one-shots, always
  true for cron): the **pause toggle** disables once a one-shot is done (nothing left
  to pause). **Run-now ▶ is enabled whenever the job isn't deleted and a run isn't
  already in flight** (`lastRunStatus != RUNNING`, PR #84), NOT gated on
  `hasFutureRuns` — so a job runs on demand any time, before or after its scheduled
  run (PR #83). The ▶ button renders under **`canControl`** (desktop always; mobile
  when the relay link is UP, firing the `RUN_JOB` RPC above); **Edit/Delete stay
  `isAdmin`-only** (desktop). While a run is in flight the row shows a spinner +
  "Running…"; on completion it switches to the result text. **Edit stays enabled** so
  a completed one-shot can be reopened and given a new time.
- **Schedule UI mirrors the mobile clock screens** (desktop has no alarm/timer icons
  by design, but reuses the layout): **Repeat** = the alarm flow (time-of-day + day
  chips → a 5-field cron `min hour * * dows`, `*` = daily), **Once** = the timer flow
  (h/m/s duration → one-shot `fireAt = now + duration`). **No `DatePicker`** — its
  desktop kotlinx-datetime calendar model throws `NoSuchMethodError`. Desktop uses
  **`TimeInput`** (text fields), not the analog clock, whose selector knob obscured
  the digit under the monochrome theme; mobile keeps the clock dial.
- **Desktop monochrome theme** gained the Material3 `surfaceContainer*` roles
  (neutral greys). Unset, they fell back to M3's purple baseline, tinting
  `AlertDialog`/menus light purple.
- **Schema:** migration `9.sqm` (v9→v10) adds `jobs` + `job_runs` (`Jobs.sq`); cron
  parsing/next-fire via `cron-utils`, **desktopMain only**.
- **Mobile completion signals (PR #85), mobile-only + foreground-only.** Two cues
  fire when a desktop run finishes and lands on the phone via sync (jobs run only on
  the desktop). Both read the same reactive `JobRepository.flow()` and treat a run as
  *noteworthy* only when `lastRunStatus ∈ {SUCCEEDED, FAILED}` (RUNNING/CANCELLED never
  signal — `Job.noteworthyRunAtEpochMs`):
  - **Chat-header count bubble** on the Jobs icon (`BadgedBox` + a tinted numbered
    `Badge` in `ChatScreen`, the same style the alarm/timer/todo icons used pre-PR #26
    — the bubble matches the icon's `LocalContentColor` so it reads as part of the icon).
    `JobBadge.unseenCount` = number of jobs whose latest run is noteworthy and newer than
    a persisted **seen** watermark; opening the Jobs screen calls `JobsViewModel.markSeen()`
    (a `LaunchedEffect(jobs)` so it re-marks on entry *and* on a completion while the
    screen is open) → watermark advances → count drops to 0 and the bubble disappears.
  - **Android OS notification** (`AndroidNotificationPresenter`, the first binding of the
    commonMain `NotificationPresenter` seam; new `NotificationKind.JOB`, `job_runs`
    channel, distinct success/error copy). `JobCompletionNotifier` observes the flow and
    notifies each newly-noteworthy run, deduped by a persisted **notified** watermark.
    The **first emission per process is a baseline** (advance the watermark, don't
    notify) so the initial sync backfill of already-finished jobs doesn't storm.
  - Both watermarks live in `JobNotificationPrefs` (Android `SharedPreferences` =
    `job_notify_prefs`), mirroring `SyncWatermarkStore`. `JobBadge`/`JobCompletionNotifier`/
    `JobNotificationPrefs` bind **only in `androidModule`** → null on desktop
    (`JobsViewModel.badge` + `ChatScreen` resolve via `getOrNull`). The notifier is
    started/stopped by the `WatchdogForegroundGate` (foreground-only, matching mobile
    sync). No new permission (POST_NOTIFICATIONS already handled) and no foreground
    service. Desktop already shows live job state, so it gets neither cue.
- **Jobs list is sorted by last-run time (PR #85), both platforms.** `JobsViewModel.jobs`
  maps the repo flow through `sortedWith(compareByDescending { lastRunAtEpochMs ?: Long.MIN_VALUE })`
  — most-recently-run at the top, never-run jobs (null) at the bottom. The query is
  creation-order and `sortedWith` is stable, so a brand-new unrun job lands at the very
  bottom. Sorting in the shared VM covers desktop and mobile in one place.
- **`run job <name> <keyword(s)>` inline chat command (PR #88).** A user can fire a job
  from the chat box — `run job property search Westport, Ontario` runs the job named
  "Property Search" with its Keyword(s) replaced by "Westport, Ontario" and renders the
  output **directly as the assistant turn (markdown, NO LLM)** in the current conversation
  thread (NOT the job's own thread — no conversation/run/last-run rows are written). It
  works like the deterministic WEATHER/FINANCE cards (CLAUDE.md #32/#33), not search
  grounding: the raw markdown/tables/links ARE the answer, so the output renders with
  `renderMarkdown=true` (links tappable, PR #82) and the LLM is never invoked — the user
  can ask the LLM follow-ups since the output is persisted in the thread. `RunJobDetector`
  (anchored `^run job\b`) + `RunJobResolver` (longest job-name token-prefix match against
  the synced `JobRepository.snapshot()`; empty keyword(s) → the job's saved `prompt`) run
  in `AgentLoop` above pre-flight; not-found / failure short-circuit deterministically
  (plain text). **Execution is behind the `InlineJobRunner` seam:** desktop runs it
  locally via `JobExecutor.runCapture` (side-effect-free, cancellation-aware —
  `destroyForcibly` on cancel); mobile runs it on the paired desktop over the **streaming**
  `RUN_JOB_INLINE` relay method so the chat **Cancel** button kills the run (CANCEL frame →
  desktop cancels `runCapture`). An in-progress "Running job: <name>…" chip
  (`AgentEvent.JobStarted` → `SearchStatus.RunningJob`) shows until the output renders or
  errors. Full as-built rationale: CLAUDE.md invariant #59.

The numbered design sections below remain accurate for the scheduler/executor
mechanics, persistence conventions, sync envelope reuse, Koin wiring, and the
trust-boundary rationale — read them with the §0 deviations applied.

## 1. What a "job" is

A **job** is time-triggered work that runs **only on the desktop agent**. Two
flavours of action:

- **Command** — a system/shell command, run like cron (the primary ask).
- **Agent prompt** — a chat/agent turn run on a schedule (e.g. "summarize unread
  email at 8am"), reusing the existing background `TaskQueue`.

Two scheduling modes:

- **Cron** — a standard 5-field cron expression for recurring runs.
- **One-shot** — run once at an absolute wall-clock instant, then mark done.

Jobs **execute exclusively on the desktop** (it has the OS shell, the warm model,
and a persistent background runtime). The mobile app is a **remote view + limited
control** surface when paired (over the Secure Gateway relay; the LAN link was
removed in PR #80, CLAUDE.md #56).

## 2. Ownership & trust boundary (security)

This is the load-bearing rule, because a job can run arbitrary OS commands:

| Action | Desktop | Mobile (linked) | Mobile (unlinked) |
|---|---|---|---|
| Create / edit / delete | ✅ | ❌ | ❌ |
| Run now | ✅ | ❌ | ❌ |
| View list + status / last result | ✅ | ✅ | ❌ (icon hidden) |
| Pause / resume | ✅ | ✅ | ❌ |

- **Commands are only ever *defined* on the desktop**, a locally-trusted context.
  The mobile link can never introduce or mutate a command string → **no remote
  code execution** even if the paired phone is compromised.
- Enforcement point: the desktop's `LinkSyncService.applyFromPeer` accepts only
  `paused` (and benign status) changes to **existing** jobs from a remote peer, and
  **rejects remote inserts and job tombstones**. (See §6.)
- The link is gated by the Secure Gateway relay pairing (E2EE; the phone scans the
  desktop's subscription-gated relay QR — PR #80). Pause/resume rides the normal
  authenticated sync path. (Pre-#80 this was the LAN QR pairing bearer token, PR #57.)
- Jobs run with the desktop user's privileges. The UI shows the full job list +
  command text so scheduled commands are auditable. (No privilege escalation, no
  "run as another user" in v1.)

## 3. Data model (commonMain — the synced shape)

Mirrors the existing sync records (`ConversationSyncRecord` / `MemorySyncRecord` in
`shared/src/commonMain/.../sync/SyncModels.kt`): stable UUID, `updatedAtEpochMs`,
nullable `deletedAtEpochMs` tombstone → last-write-wins + delete propagation.

```kotlin
enum class JobActionType { COMMAND, AGENT_PROMPT }
enum class JobScheduleType { CRON, ONE_SHOT }
enum class JobRunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class Job(
    val id: String,                 // stable UUID
    val name: String,
    val actionType: JobActionType,
    val command: String?,           // when COMMAND
    val prompt: String?,            // when AGENT_PROMPT
    val workingDir: String?,        // COMMAND only; default = user home
    val scheduleType: JobScheduleType,
    val cronExpr: String?,          // when CRON (5-field)
    val runAtEpochMs: Long?,        // when ONE_SHOT
    val paused: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long?,    // tombstone
)

data class JobRun(                  // execution history; drives "last result" in UI
    val id: String,
    val jobId: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val exitCode: Int?,             // COMMAND
    val status: JobRunStatus,
    val outputTail: String?,        // truncated stdout+stderr (e.g. last ~8 KB)
)
```

## 4. Persistence (SQLDelight, both platforms)

- New `jobs` + `job_runs` tables via a new `.sqm` migration. **Follow invariant
  #20** exactly: (a) generate the `<currentVersion>.db` schema snapshot **first**
  while `.sq` is still old; (b) write the `.sqm`; (c) update the `.sq`; (d) rebuild.
  New columns declared at the **end** of `CREATE TABLE` (column-order drift check).
  `job_runs` FK → `jobs(id)` `ON DELETE CASCADE` (free cleanup, cf. invariant #39).
- A `JobRepository` interface in commonMain with a SQLDelight impl (like
  `SqlDelightTaskRepository`). **Both platforms store rows** so the mobile app can
  render synced state offline; the **desktop is authoritative** for definitions.

## 5. Scheduler (desktopMain) — reuse `DesktopAlarmScheduler`

A new `DesktopJobScheduler` modeled on
`shared/src/desktopMain/.../clock/DesktopAlarmScheduler.kt`:

- Reuse its **drift-tolerant chunked `waitUntil`** coroutine pattern (chunks the
  delay, recomputes remaining time each chunk so it survives laptop sleep / clock
  drift). Idempotent per job id: re-arming an id cancels its prior coroutine.
- Reuse the **`rearmAll()` on startup** pattern from `ClockService` — walk the
  repository and (re)arm every non-paused, non-deleted job. Wire `rearmAll()` into
  `desktopApp/.../Main.kt` next to the existing `ClockService.rearmAll()` /
  `taskQueue.start()` startup calls.
- **Cron** parsing / next-fire is desktop-only (JVM). Options: a small JVM
  dependency (`com.cronutils:cron-utils`) added to `desktopMain`, or a hand-rolled
  5-field parser. Recommendation: `cron-utils` (well-tested, handles ranges/steps).
  Keep cron evaluation **out of commonMain** — the synced model only carries the
  `cronExpr` string; only the desktop interprets it.
- **One-shot**: arm a single fire at `runAtEpochMs`; on fire, mark the job done
  (set `paused`/terminal or tombstone-free "completed" state — final detail TBD in
  impl). A one-shot whose time already passed at startup fires immediately (or is
  marked missed — decide in impl; lean "fire once on next startup" like alarms).
- On fire: hand off to the `JobExecutor` (§6) and, for cron jobs, schedule the next
  occurrence.

## 6. Executor (desktopMain) — reuse the `LlamaServerProcess` subprocess pattern

A new `JobExecutor`. For **COMMAND** actions, reuse the exact `ProcessBuilder`
shape from `shared/src/desktopMain/.../inference/LlamaServerProcess.kt:49-101`:

- `ProcessBuilder(shellCmd).directory(workingDir).redirectErrorStream(true)`.
- Background daemon reader thread draining `inputStream`, capturing an **output
  tail** (ring-buffer the last ~8 KB) for the `JobRun`.
- `waitFor(timeout, SECONDS)`; if still alive → `destroyForcibly()`. Record
  `exitValue()`. A JVM shutdown hook ensures child processes die with the app
  (same as `LlamaServerProcess`).
- **Host default shell**, selected from `System.getProperty("os.name")` (reuse the
  helper pattern at `LlamaServerBinaryStore.kt:134`):
  - Linux / macOS → `listOf("sh", "-c", command)`
  - Windows → `listOf("powershell", "-Command", command)`

For **AGENT_PROMPT** actions: **enqueue a `QueuedTask` into the existing
`TaskQueue`** (`shared/src/commonMain/.../task/TaskQueue.kt`) rather than running
inline — reuse its durable single-consumer queue, progress, retry, and
orphan-requeue. The `JobRun` references the resulting task.

On completion/failure: write a `JobRun` row and emit a best-effort notification —
`present(AppNotification(id = "job:<id>", kind = TASK, …))` through the existing
`NotificationPresenter` seam (→ `TrayNotificationPresenter` when a tray exists,
silently a logging no-op otherwise, per the PR #60 tray fix).

## 7. Sync — extend the existing `SyncBundle` envelope (no new endpoints)

The link (PR #57) already runs a generic reconcile loop over an extensible bundle:
`SyncController` ↔ `/sync/changes` (pull) + `/sync/upsert` (push) +
`/sync/subscribe` (SSE wake), last-write-wins + tombstones, in
`shared/src/commonMain/.../sync/`.

- Add `jobs: List<JobSyncRecord> = emptyList()` to `SyncBundle` (`SyncModels.kt`).
  Job rows then flow through the **existing** reconcile path exactly like
  conversations/memories — no new HTTP routes, no new client.
- **Desktop → mobile:** full job + last-run state pushed on change.
- **Mobile → desktop:** the only legal mutation is a `paused` toggle on an existing
  job. **Enforce in `SqlDelightLinkSyncService.applyFromPeer` on the desktop:**
  for incoming job records from a peer, apply `paused`/status to an existing row
  only; **drop remote inserts and remote tombstones.** This is the §2 trust
  boundary in code. Mobile applies all desktop-originated job records verbatim.
- Pausing from mobile = set `paused = true`, bump `updatedAtEpochMs`, push. Desktop
  applies → `DesktopJobScheduler` cancels that job's coroutine. Resume re-arms.
- `LocalChangeBus` fires on desktop job writes so the SSE wakes the phone promptly.

## 8. UI (`:ui` commonMain) — chat icon + Jobs screen

- **Chat top bar** (`ui/src/commonMain/.../ui/chat/ChatScreen.kt`): add a Jobs
  `IconButton` immediately **before** the Settings icon (after the
  `if (!isDesktopPlatform) { … }` mobile-only clock block). The icon shows on
  **both** platforms; enablement:
  ```kotlin
  // desktop: always enabled. mobile: enabled only when linked & UP.
  val jobsEnabled = isDesktopPlatform || desktopLinkStatus == DesktopLinkStatus.UP
  ```
  reading `DesktopLinkStatusProvider.status` (the provider is already injected for
  the link indicator at `ChatScreen.kt:993-1012`). Proposed icon:
  `Icons.Filled.Schedule` (falls back to `Work`/`EventNote`).
- **Navigation:** add `MainRoute.JobManagement` (+ Saver encode/restore) in
  `ui/.../navigation/MainRoute.kt`; thread an `onOpenJobs` callback through
  `ChatScreen`; wire `MainRoute.JobManagement -> JobsScreen(...)` in
  `ui/.../navigation/AppNavHost.kt` (mirrors Settings / Todo / Timer routes).
- **`JobsScreen` + `JobsViewModel`:** a list of jobs — name, schedule (cron/next
  run), last result + status, and a pause toggle.
  - **Create / edit / delete + "Run now" render only on desktop**
    (`isDesktopPlatform`). New-job form: name; action type (Command | Agent prompt);
    command or prompt; schedule (cron expression **or** date/time picker); working
    dir (command only).
  - **Mobile** shows the same list read-only, with pause/resume enabled. No
    add/edit/delete/run affordances.

## 9. Koin wiring

- `JobRepository` → `agentCoreModule` (commonMain), SQLDelight-backed on both
  platforms.
- `DesktopJobScheduler`, `JobExecutor` → `desktopModule` (desktop only). Started
  from `Main.kt` (`rearmAll()` / `start()`).
- `JobsViewModel` → `uiModule` via `viewModelOf` (commonMain).
- Mobile binds the repository (for synced display) but **no** scheduler/executor.

## 10. Build / invariant touchpoints

- SQLDelight migration: invariant **#20** (snapshot-first dance, columns at end,
  committed `.db` per version for `verifyMigrations`).
- `cron-utils` is **desktop-only** — do not leak into commonMain (#cross-platform).
- New AGP-9 KMP source-set dep DSL: add `cron-utils` at the configuration level if
  an `exclude` is ever needed (invariant #19), else a plain `desktopMain`
  `implementation`.
- Tray notifications for job results are best-effort (PR #60 tray fix) — never the
  only surface; the Jobs screen is authoritative.

## 11. Out of scope (v1)

- Recurring **agent-prompt** result delivery beyond a tray toast + history row.
- Per-OS command variants (a job's command is one string run by the host shell;
  a job authored against a Linux desktop may not be portable to a Windows desktop —
  acceptable since jobs run only on the desktop that owns them).
- Mobile-initiated create/run, allowlists, sandboxing, "run as" — explicitly
  excluded by the §2 trust model.
- Catch-up semantics for cron runs missed while the desktop was off (v1 fires the
  next future occurrence; no backfill).
