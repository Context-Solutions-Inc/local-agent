# Headless / startup service templates

Operator templates for running the desktop agent (`:desktopApp`) as a background
service via `LOCALAGENT_HEADLESS=1`. These are **not** built into the app — copy and
edit the paths to match your install.

| File | Platform | Installs to |
|---|---|---|
| `localagent.user.service` | Linux systemd (per-user) | `~/.config/systemd/user/localagent.service` |
| `localagent.system.service` | Linux systemd (system-wide) | `/etc/systemd/system/localagent.service` |
| `com.contextsolutions.localagent.plist` | macOS launchd | `~/Library/LaunchAgents/` |

Windows uses Task Scheduler / the startup folder — no template file; see
`docs/DESKTOP_PACKAGING.md` → "Headless / standalone deployment".

Full step-by-step (build → install → provision models → enable service) lives in
`docs/DESKTOP_PACKAGING.md`.
