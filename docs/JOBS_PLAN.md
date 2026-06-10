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
  if present, else creates one, appending `user = prompt` then `assistant =
  response` (`renderMarkdown = false`). The conversation syncs through the normal
  `ConversationSyncRecord` path, so **"View conversation"** on either platform opens
  it in Chat via `ChatViewModel.loadConversation` (the conversation-history resume
  flow). The job row denormalises the latest run (`last_run_status/at/summary` +
  `last_run_conversation_id`).
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
  true for cron): Run-now + the pause toggle disable once a one-shot is done; **Edit
  stays enabled** so a completed one-shot can be reopened and given a new time (it
  re-runs into the same conversation thread); Delete always enabled.
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
