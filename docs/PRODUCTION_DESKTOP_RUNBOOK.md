# Production Desktop Build Run Book

End-to-end, copy-pasteable procedure for cutting **signed, tested production
installers** of the Compose-Multiplatform desktop app (`:desktopApp`) on **Linux,
macOS, and Windows**. You build **on each OS in turn** (jpackage cross-builds nothing —
one machine per OS), sign, install, and smoke-test.

This is the *operational* companion to `docs/DESKTOP_PACKAGING.md` (which covers the
*strategy* — native-runtime choices, GPU opt-in, headless deployment). When the two
disagree on the LLM runtime, **this doc is current**: the LLM now runs as a
`llama-server` subprocess (the old `net.ladenthin:llama` / `-PllamaLibPath` JNI path in
`DESKTOP_PACKAGING.md` is retired).

> **Scope.** Desktop only (Linux/macOS/Windows). iOS is a separate launch, after the
> desktop app is validated on all three OSes. **Deferred this launch:** ProGuard/R8
> minification (CI/run book use the non-minified distribution — the reflection/JNI-heavy
> deps need a keep-rules pass first); Linux package signing (the `.deb`/`.rpm` ship
> unsigned — repo GPG signing is a later hardening step).

---

## 1. Scope & prerequisites

You need **three build hosts** — one Linux, one macOS, one Windows — each with:

| Host | Always | Packaging tools | Signing tools |
|---|---|---|---|
| **Linux** (x86_64) | JDK 17 (Temurin), `git`, internet | `fakeroot` (`.deb`), `rpm`/`rpmbuild` (`.rpm`) | — (unsigned) |
| **macOS** (Apple Silicon or Intel) | JDK 17 (Temurin), `git`, internet | none beyond Xcode CLT | Xcode Command Line Tools, Developer ID certs (below) |
| **Windows** (x86_64) | JDK 17 (Temurin), `git`, internet | **WiX Toolset v3** (for `.msi`) | Windows SDK `signtool`, code-signing cert (below) |

Notes:
- **JDK must be 17** and must bundle `jpackage` (Temurin 17 does). It matches the
  project's `jvmToolchain(17)`. Don't use 21+ — the desktop runtime image and a pinned
  markdown-renderer dep target Java 17.
- The Gradle **wrapper is checked in** (`android-app/gradlew`, Gradle 9.3.1) — do not
  install Gradle separately. On Windows, run `./gradlew` from **Git Bash** (the CI uses
  the same shell on `windows-latest`).
- Set `JAVA_HOME` to the Temurin 17 JDK on each host before building.

---

## 2. Pre-build setup (do once per host, every release)

### 2a. Publish the Secure Gateway SDK to mavenLocal — REQUIRED

The desktop relay/subscription code (`:shared/desktopMain`) depends on
`com.securegateway:java:0.1.0`, consumed from **`mavenLocal()`** (see
`android-app/settings.gradle.kts`). It is **not** on Maven Central — if it's missing the
desktop build fails at dependency resolution. Publish it from the sibling repo first:

```bash
cd ../secure-gateway/sdk        # sibling of this repo
./gradlew publishToMavenLocal
# verify:
ls ~/.m2/repository/com/securegateway/java/0.1.0/
```

Do this on **every** build host (the artifact lives in each host's `~/.m2`).

### 2b. Check out the release tag, clean

Build identity (`versionCode`, `gitDescribe`) is read from `git` at configuration time.
Build from a **clean, tagged checkout** so the About dialog doesn't show `-dirty`:

```bash
cd <repo>
git fetch --tags
git checkout vX.Y.Z          # the release tag
git status                   # must be clean — no uncommitted changes
```

Confirm the two versions in `android-app/desktopApp/build.gradle.kts`:
- `appVersionName = "0.1.0"` — the **user-facing** version (About dialog), kept in
  lockstep with `androidApp`.
- `packageVersion = "1.0.0"` — the **installer/upgrade-detection** version only.
  jpackage requires MAJOR > 0, so it's deliberately decoupled from `0.1.0`. Bump both in
  lockstep once the release reaches 1.x.

### 2c. Aux-model hosting (optional — see §6)

The ONNX classifier + embedder **auto-download from the CDN on first run** (PR #3), so a
stock build needs no decision here. Only if you want to serve them from your own host
(`-PauxModelBaseUrl`) or provision them per-machine (air-gapped) does the build command in
§3–§5 change. Otherwise skip ahead.

---

## 3. Build — Linux (`.deb` + `.rpm`)

```bash
# one-time host setup
sudo apt-get update && sudo apt-get install -y fakeroot rpm

cd <repo>/android-app

# smoke gate (no OS packaging tools needed — proves the config + app-image):
./gradlew :desktopApp:createDistributable
# → desktopApp/build/compose/binaries/main/app/LocalAgent/   (run bin/LocalAgent in place)

# production installers for this host OS:
./gradlew :desktopApp:packageDistributionForCurrentOS
#   add  -PauxModelBaseUrl=https://your.cdn/localagent/aux-models/v1.0.0   only to override the default CDN (see §6)
#   add  -PonnxGpu=true   only for a CUDA build (niche; CPU is the default, always-works)
```

Outputs:
- `desktopApp/build/compose/binaries/main/deb/local-agent_<ver>_amd64.deb`
- `desktopApp/build/compose/binaries/main/rpm/local-agent-<ver>.x86_64.rpm`

Single-format alternatives: `:desktopApp:packageDeb`, `:desktopApp:packageRpm`.

Linux installers are **unsigned** this launch (no Gatekeeper/SmartScreen equivalent;
repo GPG signing deferred).

---

## 4. Build — macOS (`.dmg` + `.pkg`, signed + notarized)

### 4a. One-time host + Apple setup

1. Install **Xcode Command Line Tools**: `xcode-select --install`.
2. From your Apple **Developer account**, create + install a **"Developer ID
   Application"** certificate (for distribution outside the App Store) into the login
   keychain. Find its exact identity string:
   ```bash
   security find-identity -v -p codesigning
   # → "Developer ID Application: Context Solutions (TEAMID)"
   ```
3. Create an **app-specific password** for the Apple ID used to notarize
   (appleid.apple.com → Sign-In and Security). Optionally store it in the keychain so it
   isn't in your shell history:
   ```bash
   xcrun notarytool store-credentials NOTARIZATION_PASSWORD \
     --apple-id "you@contextsolutions.ca" --team-id TEAMID --password <app-specific-pw>
   # then reference it below as  @keychain:NOTARIZATION_PASSWORD
   ```

### 4b. Export credentials, then build

Signing is **env-gated** in `build.gradle.kts` — it only engages when
`MAC_SIGN_IDENTITY` is set, so an operator without certs still gets an (unsigned) build.

```bash
export MAC_SIGN_IDENTITY="Developer ID Application: Context Solutions (TEAMID)"
export NOTARIZATION_APPLE_ID="you@contextsolutions.ca"
export NOTARIZATION_PASSWORD="@keychain:NOTARIZATION_PASSWORD"   # or the raw app-specific pw
export NOTARIZATION_TEAM_ID="TEAMID"

cd <repo>/android-app
./gradlew :desktopApp:notarizeDmg :desktopApp:notarizePkg
#   add  -PauxModelBaseUrl=...  only to override the default CDN (see §6)
```

`notarizeDmg`/`notarizePkg` build the app-image, codesign it with `MAC_SIGN_IDENTITY`,
package the installer, submit to Apple's notary service, and wait for the result.

Outputs:
- `desktopApp/build/compose/binaries/main/dmg/LocalAgent-<ver>.dmg`
- `desktopApp/build/compose/binaries/main/pkg/LocalAgent-<ver>.pkg`

### 4c. Verify (staple + Gatekeeper)

```bash
# staple the notarization ticket so the installer validates offline:
xcrun stapler staple desktopApp/build/compose/binaries/main/dmg/LocalAgent-*.dmg
xcrun stapler staple desktopApp/build/compose/binaries/main/pkg/LocalAgent-*.pkg

# confirm Gatekeeper accepts it (after installing the .app):
spctl --assess --type execute -vv /Applications/LocalAgent.app
codesign --verify --deep --strict --verbose=2 /Applications/LocalAgent.app
```

A clean install should launch with **no "unidentified developer" warning**.

> If you don't have certs yet: omit the `export`s and run
> `./gradlew :desktopApp:packageDmg :desktopApp:packagePkg` for **unsigned** installers
> (testers must right-click → Open, or `xattr -dr com.apple.quarantine` the `.app`).

---

## 5. Build — Windows (`.msi` + `.exe`, signed)

The Compose plugin packages the installers but **does not** Authenticode-sign them, so
signing is a **post-build `signtool`** step.

### 5a. One-time host setup

1. Install **WiX Toolset v3** (required for `.msi`).
2. Install the **Windows SDK** (provides `signtool.exe`).
3. Obtain a **code-signing certificate** (OV or EV). Have it in the Windows cert store,
   or as a `.pfx` + password. (EV certs avoid SmartScreen reputation warnings sooner.)

### 5b. Build (Git Bash)

```bash
cd <repo>/android-app
./gradlew :desktopApp:packageMsi :desktopApp:packageExe
#   add  -PauxModelBaseUrl=...  only to override the default CDN (see §6)
```

Outputs:
- `desktopApp/build/compose/binaries/main/msi/LocalAgent-<ver>.msi`
- `desktopApp/build/compose/binaries/main/exe/LocalAgent-<ver>.exe`

> The MSI's `upgradeUuid` (`b6c3f2a4-…-4f33`) is **stable across releases** so upgrades
> are recognized as upgrades, not side-by-side installs. Never regenerate it.

### 5c. Sign the installers (PowerShell or cmd)

```powershell
# Using a cert in the store (by subject), with a timestamp (timestamp is mandatory —
# it keeps the signature valid after the cert expires):
$ts = "http://timestamp.digicert.com"
$out = "desktopApp\build\compose\binaries\main"
signtool sign /fd SHA256 /tr $ts /td SHA256 /n "Context Solutions" "$out\msi\LocalAgent-*.msi"
signtool sign /fd SHA256 /tr $ts /td SHA256 /n "Context Solutions" "$out\exe\LocalAgent-*.exe"

# Or from a .pfx:
# signtool sign /f cert.pfx /p <pw> /fd SHA256 /tr $ts /td SHA256 "<file>"

# verify:
signtool verify /pa /v "$out\msi\LocalAgent-*.msi"
```

A signed installer shows a real publisher in the UAC prompt. With a fresh OV cert,
SmartScreen may still warn until reputation builds; an EV cert clears it immediately.

---

## 6. Model & secret provisioning

The installer ships the app + JDK runtime + small bundled assets (vocab, configs,
icon) — but **no models**. Models download into the app-data dir at first run, or are
staged per-machine.

App-data dir per OS:
- Linux: `~/.local/share/LocalAgent/`
- macOS: `~/Library/Application Support/LocalAgent/`
- Windows: `%LOCALAPPDATA%\LocalAgent\`

### What downloads at first run (automatic)

| Artifact | Source | Notes |
|---|---|---|
| **llama-server binary** | GitHub `ggml-org/llama.cpp` release (pinned `LlamaServerRelease.TAG`), CPU or Vulkan per host | runs the LLM as a subprocess; CPU is the always-works default. `LOCALAGENT_LLAMA_SERVER_VARIANT=cpu\|vulkan\|auto` |
| **Gemma GGUF** (Q4_K_M, ~3.1 GB) | HuggingFace `unsloth/gemma-4-E2B-it-GGUF` (sha256/size pinned in `DesktopModelStore`) | public repo — no token needed. Set `HF_AUTH_TOKEN` only if you point at a gated mirror |
| **Vosk STT model** (~41 MB) | `alphacephei.com` | optional; dictation no-ops without it |
| **Piper voice + binary** | `rhasspy/piper` + HF `rhasspy/piper-voices` | optional; only if the user picks the "piper" neural read-aloud engine |

### ONNX classifier + embedder

The pre-flight classifier (`preflight_memory_shared_v1.0.0.onnx`, ~266 MB) and MiniLM
embedder (`all-MiniLM-L6-v2.onnx`, ~91 MB) **auto-download from the CDN on first run**
(default `DesktopAuxModels.DEFAULT_BASE_URL`, PR #3) and are verified against the
sha256/size pinned in code (`DesktopAuxModels`). A stock build needs nothing extra.

**Option A — use your own host (optional).** To serve the two `.onnx` from a different
base URL, override it at build time:
```bash
./gradlew :desktopApp:packageDistributionForCurrentOS \
  -PauxModelBaseUrl=https://your.cdn/localagent/aux-models/v1.0.0
```
The app then fetches `…/<base>/preflight_memory_shared_v1.0.0.onnx` and
`…/<base>/all-MiniLM-L6-v2.onnx` at first run and verifies the pinned checksums.

**Option B — provision per machine (air-gapped / offline).** Drop the files into
`<app-data>/models/` or point env overrides at them:
```bash
export LOCALAGENT_CLASSIFIER_ONNX=/abs/path/preflight_memory_shared_v1.0.0.onnx
export LOCALAGENT_EMBEDDER_ONNX=/abs/path/all-MiniLM-L6-v2.onnx
```

> **If the aux models are absent (e.g. CDN unreachable and no override), search
> silently under-fires.** The pre-flight classifier no-ops → most queries won't trigger
> a web search (an explicit `web search …` command still works).

### Runtime env vars (provisioning / deployment, not build)

| Var | Purpose |
|---|---|
| `HF_AUTH_TOKEN` | only if the GGUF is behind a gated HF repo/mirror |
| `LOCALAGENT_LLAMA_SERVER` / `LOCALAGENT_LLAMA_SERVER_VARIANT` | pin a prebuilt server binary / force cpu\|vulkan\|auto |
| `LOCALAGENT_CLASSIFIER_ONNX` / `LOCALAGENT_EMBEDDER_ONNX` | Option B aux-model paths |
| `LOCALAGENT_VOSK_MODEL` / `LOCALAGENT_PIPER_BINARY` | pre-staged STT/TTS assets |
| `LOCALAGENT_GATEWAY_URL` / `LOCALAGENT_SUBSCRIPTION_PORTAL_URL` | Secure Gateway relay + billing portal (anywhere-access feature) |
| `LOCALAGENT_KEYSTORE_PASSWORD` | password for the encrypted PKCS#12 secret store |
| `LOCALAGENT_HEADLESS=1` | start the background runtime without opening a window (see `DESKTOP_PACKAGING.md` → headless deployment) |

The **Brave Search key** is entered per-user via Settings (BYOK); it is not baked into
the desktop build.

---

## 7. Install & smoke test (per OS)

Install the produced installer, then on first launch:

1. **Install:**
   - Linux `.deb` → `sudo dpkg -i local-agent_*.deb` (installs to
     `/opt/local-agent/bin/LocalAgent`); `.rpm` → `sudo rpm -i …`.
   - macOS `.dmg`/`.pkg` → `/Applications/LocalAgent.app`.
   - Windows `.msi`/`.exe` → per-user `%LOCALAPPDATA%\LocalAgent\LocalAgent.exe`.
2. **First-run downloads:** launch the GUI. It pulls the llama-server binary + GGUF
   (+ ONNX aux models if Option A) into the app-data dir — watch progress. Multi-GB; be
   patient on first run.
3. **Functional smoke:**
   - **Chat:** send a plain prompt → streamed answer.
   - **Search:** ask something current (e.g. "what's the weather in Toronto") → a search
     chip appears and a grounded answer renders. (If search never fires, the aux models
     are missing — see §6.)
   - **Voice (optional):** toggle the mic (dictation), trigger read-aloud on a reply.
   - **Headless:** quit the GUI, then `LOCALAGENT_HEADLESS=1 <path>/LocalAgent` →
     starts minimized to tray (graphical session) or windowless (server). Stop with the
     tray's **Shut down** or Ctrl-C/SIGTERM.
4. **Identity:** open **About** → confirm version `0.1.0` and the expected git describe
   (no `-dirty`).
5. **Trust (signed builds):** macOS launches with no "unidentified developer" prompt
   (`spctl --assess` passes); Windows shows the real publisher in UAC.

---

## 8. Release checklist

- [ ] §2a SDK published to mavenLocal on all three hosts.
- [ ] §2b clean checkout of `vX.Y.Z`, `git status` clean; versions confirmed.
- [ ] §6 aux-model strategy decided (Option A URL ready, or Option B files staged).
- [ ] **Linux:** `.deb` + `.rpm` built; `createDistributable` smoke passed.
- [ ] **macOS:** `.dmg` + `.pkg` built, **signed + notarized + stapled**; `spctl` passes.
- [ ] **Windows:** `.msi` + `.exe` built and **signtool-signed**; `signtool verify` passes.
- [ ] §7 install + first-run + chat/search/voice/headless smoke passed on each OS.
- [ ] About shows the right version/git on each OS.
- [ ] Installers collected for distribution.
- [ ] Known gaps acknowledged: Linux unsigned; ProGuard minify deferred.

---

## 9. References

- `docs/DESKTOP_PACKAGING.md` — native-runtime strategy, GPU opt-in, headless/service
  deployment + the systemd/launchd templates in `android-app/desktopApp/packaging/`.
- `.github/workflows/desktop-package.yml` — the tag-triggered CI matrix (same gradle
  tasks; useful if you later move off local builds).
- `docs/ANYWHERE_ACCESS_PLAN.md` — Secure Gateway relay + subscription
  (`LOCALAGENT_GATEWAY_URL`, the `com.securegateway:java` SDK).
- Signing DSL: JetBrains Compose Multiplatform "Signing and notarization on macOS"
  tutorial; Kotlin "Native distributions" docs.
