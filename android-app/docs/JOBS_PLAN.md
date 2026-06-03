# Jobs — design plan

Status: **design (not yet implemented).** This doc is the reviewable design; the
implementation lands in a later PR. The desktop **tray-robustness fix** that ships
alongside this doc (PR #60) is a prerequisite — it makes tray notifications a
best-effort enhancement so the in-window Jobs screen can be the source of truth.

Tracking: see `PHASE1_PLAN.md` / CLAUDE.md for where this slots in. Numbered
"invariants" referenced below are CLAUDE.md hard invariants.

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
control** surface when LAN-linked.

## 2. Ownership & trust boundary (security)

This is the load-bearing rule, because a job can run arbitrary OS commands:

| Action | Desktop | Mobile (linked) | Mobile (unlinked) |
|---|---|---|---|
| Create / edit / delete | ✅ | ❌ | ❌ |
| Run now | ✅ | ❌ | ❌ |
| View list + status / last result | ✅ | ✅ | ❌ (icon disabled) |
| Pause / resume | ✅ | ✅ | ❌ |

- **Commands are only ever *defined* on the desktop**, a locally-trusted context.
  The mobile link can never introduce or mutate a command string → **no remote
  code execution** even if the paired phone is compromised.
- Enforcement point: the desktop's `LinkSyncService.applyFromPeer` accepts only
  `paused` (and benign status) changes to **existing** jobs from a remote peer, and
  **rejects remote inserts and job tombstones**. (See §6.)
- The LAN link is still gated by the QR pairing bearer token (PR #57). Pause/resume
  rides the normal authenticated sync path.
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
