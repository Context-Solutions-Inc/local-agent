# Production Build & Deployment Run Book

End-to-end, copy-pasteable procedure for cutting **signed, tested production
builds** of Local Agent on **both platforms**:

- **Desktop** — the Compose-Multiplatform app (`:desktopApp`) on **Linux, macOS, and
  Windows**. You build **on each OS in turn** (jpackage cross-builds nothing — one
  machine per OS), sign, install, and smoke-test.
- **Mobile** — the Android app (`:androidApp`) for **Pixel 7 / Android 16** (the Phase 1
  target). You build a minified, signed APK/AAB, then distribute via Google Play
  (AAB + Play App Signing) or a direct APK.

This is the *operational* companion to `docs/DESKTOP_PACKAGING.md` (which covers the
desktop *strategy* — native-runtime choices, GPU opt-in, headless deployment). When the
two disagree on the LLM runtime, **this doc is current**: the desktop LLM runs as a
`llama-server` subprocess (the old `net.ladenthin:llama` / `-PllamaLibPath` JNI path in
`DESKTOP_PACKAGING.md` is retired).

> **Scope.** Desktop = Linux/macOS/Windows. Mobile = Android only (iOS is stubbed for
> Phase 2). **Deferred this launch (desktop only):** ProGuard/R8 minification (the desktop
> CI/run book use the non-minified distribution); Linux package signing (the `.deb`/`.rpm`
> ship unsigned). **Mobile R8 minification is ON** and is a release requirement (see §11).

---

# Part 0 — Common prerequisites

## 0a. Publish the Secure Gateway SDK to mavenLocal — REQUIRED (both platforms)

The relay/subscription code depends on the Secure Gateway SDK consumed from
**`mavenLocal()`** (see `android-app/settings.gradle.kts`). It is **not** on Maven
Central — if it's missing, the build fails at dependency resolution. Publish it from the
sibling repo first, on **every build host** (the artifacts live in each host's `~/.m2`):

```bash
cd ../secure-gateway/sdk        # sibling of this repo
./gradlew publishToMavenLocal
```

This publishes **both** modules used by the app at the version pinned in
`android-app/gradle/libs.versions.toml` (`securegateway = "0.2.2"`):

| Module | Consumed by | Verify |
|---|---|---|
| `com.securegateway:java:0.2.2` | desktop (`:shared/desktopMain`) | `ls ~/.m2/repository/com/securegateway/java/0.2.2/` |
| `com.securegateway:android:0.2.2` | mobile (`:shared/androidMain`, the lazysodium/JNA AAR) | `ls ~/.m2/repository/com/securegateway/android/0.2.2/` |

> The desktop hosts only need `:java`; the Android build host needs `:android` (and pulls
> `:java`/`:core` transitively). Publishing both is harmless everywhere.

## 0b. JDK & toolchain

- **JDK 17 (Temurin)** on every host, with `JAVA_HOME` pointed at it. It matches the
  project's `jvmToolchain(17)`. Don't use 21+ (the desktop runtime image + a pinned
  markdown-renderer dep target Java 17).
- The Gradle **wrapper is checked in** (`android-app/gradlew`, Gradle 9.3.1) — do not
  install Gradle separately. On Windows, run `./gradlew` from **Git Bash**.
- **Mobile only:** an **Android SDK** with platform & build-tools for **API 36**; set
  `ANDROID_HOME` (and the SDK's `cmdline-tools`/`platform-tools` on `PATH`). `jpackage` is
  not used for mobile.

## 0c. Check out the release tag, clean

Build identity (`versionCode`, `gitDescribe`) is read from `git` at configuration time on
both platforms. Build from a **clean, tagged checkout** so the About dialog doesn't show
`-dirty`:

```bash
cd <repo>
git fetch --tags
git checkout vX.Y.Z          # the release tag
git status                   # must be clean — no uncommitted changes
```

Version sources (kept in lockstep across desktop + mobile):
- `appVersionName = "1.0.0"` — the **user-facing** version (About dialog on both
  platforms). Defined in `androidApp/build.gradle.kts` and `desktopApp/build.gradle.kts`.
- **Desktop** `packageVersion = appVersionName` (`1.0.0`) — the installer/upgrade-detection
  version, kept in lockstep (jpackage/macOS require MAJOR > 0, so the shared version stays
  >= 1.x; produces e.g. `local-agent_1.0.0-1_amd64.deb`).
- **Mobile** `versionCode = <git commit timestamp>` — auto-derived (`git log -1
  --format=%ct HEAD`), monotonic, so each tagged build sorts above the last for Play's
  increasing-versionCode requirement. `versionName = appVersionName`.

---

# Part A — Desktop (Linux / macOS / Windows)

You need **three build hosts** — one per OS — each with the Part 0 prerequisites plus:

| Host | Packaging tools | Signing tools |
|---|---|---|
| **Linux** (x86_64) | `fakeroot` (`.deb`), `rpm`/`rpmbuild` (`.rpm`) | — (unsigned) |
| **macOS** (Apple Silicon or Intel) | Xcode Command Line Tools | Developer ID certs (§A2) |
| **Windows** (x86_64) | **WiX Toolset v3** (`.msi`) | Windows SDK `signtool`, code-signing cert (§A3) |

## A0. Aux-model hosting (optional — see §A4)

The ONNX classifier + embedder **auto-download from the CDN on first run** (PR #3), so a
stock build needs no decision here. Only if you want to serve them from your own host
(`-PauxModelBaseUrl`) or provision them per-machine (air-gapped) does the build command
change. Otherwise skip ahead.

## A1. Build — Linux (`.deb` + `.rpm`)

```bash
# one-time host setup
sudo apt-get update && sudo apt-get install -y fakeroot rpm

cd <repo>/android-app

# smoke gate (no OS packaging tools needed — proves the config + app-image):
./gradlew :desktopApp:createDistributable
# → desktopApp/build/compose/binaries/main/app/LocalAgent/   (run bin/LocalAgent in place)

# production installers for this host OS:
./gradlew :desktopApp:packageDistributionForCurrentOS
#   add  -PauxModelBaseUrl=https://your.cdn/localagent/aux-models/v1.0.0   only to override the default CDN (see §A4)
#   add  -PonnxGpu=true   only for a CUDA build (niche; CPU is the default, always-works)
```

Outputs:
- `desktopApp/build/compose/binaries/main/deb/local-agent_<ver>_amd64.deb`
- `desktopApp/build/compose/binaries/main/rpm/local-agent-<ver>.x86_64.rpm`

Single-format alternatives: `:desktopApp:packageDeb`, `:desktopApp:packageRpm`.
Linux installers are **unsigned** this launch (repo GPG signing deferred).

## A2. Build — macOS (`.dmg` + `.pkg`, signed + notarized)

### A2a. One-time host + Apple setup

1. Install **Xcode Command Line Tools**: `xcode-select --install`.
2. From your Apple **Developer account**, create + install a **"Developer ID
   Application"** certificate into the login keychain. Find its identity string:
   ```bash
   security find-identity -v -p codesigning
   # → "Developer ID Application: Context Solutions (TEAMID)"
   ```
3. Create an **app-specific password** for the notarizing Apple ID (appleid.apple.com →
   Sign-In and Security), optionally stored in the keychain:
   ```bash
   xcrun notarytool store-credentials NOTARIZATION_PASSWORD \
     --apple-id "you@contextsolutions.ca" --team-id TEAMID --password <app-specific-pw>
   # then reference it below as  @keychain:NOTARIZATION_PASSWORD
   ```

### A2b. Export credentials, then build

Signing is **env-gated** in `build.gradle.kts` — it engages only when `MAC_SIGN_IDENTITY`
is set, so an operator without certs still gets an (unsigned) build.

```bash
export MAC_SIGN_IDENTITY="Developer ID Application: Context Solutions (TEAMID)"
export NOTARIZATION_APPLE_ID="you@contextsolutions.ca"
export NOTARIZATION_PASSWORD="@keychain:NOTARIZATION_PASSWORD"   # or the raw app-specific pw
export NOTARIZATION_TEAM_ID="TEAMID"

cd <repo>/android-app
./gradlew :desktopApp:notarizeDmg :desktopApp:notarizePkg
#   add  -PauxModelBaseUrl=...  only to override the default CDN (see §A4)
```

Outputs:
- `desktopApp/build/compose/binaries/main/dmg/LocalAgent-<ver>.dmg`
- `desktopApp/build/compose/binaries/main/pkg/LocalAgent-<ver>.pkg`

### A2c. Verify (staple + Gatekeeper)

```bash
xcrun stapler staple desktopApp/build/compose/binaries/main/dmg/LocalAgent-*.dmg
xcrun stapler staple desktopApp/build/compose/binaries/main/pkg/LocalAgent-*.pkg
spctl --assess --type execute -vv /Applications/LocalAgent.app
codesign --verify --deep --strict --verbose=2 /Applications/LocalAgent.app
```

A clean install should launch with **no "unidentified developer" warning**.

> No certs yet? Omit the `export`s and run
> `./gradlew :desktopApp:packageDmg :desktopApp:packagePkg` for **unsigned** installers
> (testers right-click → Open, or `xattr -dr com.apple.quarantine` the `.app`).

## A3. Build — Windows (`.msi` + `.exe`, signed)

The Compose plugin packages the installers but **does not** Authenticode-sign them, so
signing is a **post-build `signtool`** step.

### A3a. One-time host setup

1. Install **WiX Toolset v3** (required for `.msi`).
2. Install the **Windows SDK** (provides `signtool.exe`).
3. Obtain a **code-signing certificate** (OV or EV) in the cert store or as a `.pfx` + pw.

### A3b. Build (Git Bash)

```bash
cd <repo>/android-app
./gradlew :desktopApp:packageMsi :desktopApp:packageExe
#   add  -PauxModelBaseUrl=...  only to override the default CDN (see §A4)
```

Outputs:
- `desktopApp/build/compose/binaries/main/msi/LocalAgent-<ver>.msi`
- `desktopApp/build/compose/binaries/main/exe/LocalAgent-<ver>.exe`

> The MSI's `upgradeUuid` (`b6c3f2a4-…-4f33`) is **stable across releases** so upgrades are
> recognized as upgrades, not side-by-side installs. Never regenerate it.

### A3c. Sign the installers (PowerShell or cmd)

```powershell
$ts = "http://timestamp.digicert.com"
$out = "desktopApp\build\compose\binaries\main"
signtool sign /fd SHA256 /tr $ts /td SHA256 /n "Context Solutions" "$out\msi\LocalAgent-*.msi"
signtool sign /fd SHA256 /tr $ts /td SHA256 /n "Context Solutions" "$out\exe\LocalAgent-*.exe"
# Or from a .pfx:  signtool sign /f cert.pfx /p <pw> /fd SHA256 /tr $ts /td SHA256 "<file>"
signtool verify /pa /v "$out\msi\LocalAgent-*.msi"
```

A signed installer shows a real publisher in the UAC prompt. With a fresh OV cert,
SmartScreen may warn until reputation builds; an EV cert clears it immediately.

## A4. Desktop model & secret provisioning

The installer ships the app + JDK runtime + small bundled assets (vocab, configs, icon) —
but **no models**. Models download into the app-data dir at first run, or are staged
per-machine.

App-data dir per OS:
- Linux: `~/.local/share/LocalAgent/`
- macOS: `~/Library/Application Support/LocalAgent/`
- Windows: `%LOCALAPPDATA%\LocalAgent\`

### What downloads at first run (automatic)

| Artifact | Source | Notes |
|---|---|---|
| **llama-server binary** | GitHub `ggml-org/llama.cpp` release (pinned `LlamaServerRelease.TAG`), CPU or Vulkan per host | runs the LLM as a subprocess; CPU is the always-works default. `LOCALAGENT_LLAMA_SERVER_VARIANT=cpu\|vulkan\|auto` |
| **Gemma GGUF** (Q4_K_M, ~3.1 GB) + **mmproj-F16** (~986 MB) | R2 CDN (`gemma-4-E2B-it-Q4_K_M.gguf` / `gemma-4-E2B-it-mmproj-F16.gguf`, sha256/size pinned in `DesktopModelStore`) | **public CDN — no auth** (PR #22, moved off HuggingFace) |
| **Vosk STT model** (~41 MB) | R2 CDN (`vosk.tar.gz`, pinned in `VoskModelStore`) | optional; dictation no-ops without it (PR #22, moved off `alphacephei.com`) |
| **Piper voice + binary** | `rhasspy/piper` + HF `rhasspy/piper-voices` | optional; only if the user picks the "piper" neural read-aloud engine |

### ONNX classifier + embedder

The pre-flight classifier (`preflight_memory_shared_v1.0.0.onnx`, ~266 MB) and MiniLM
embedder (`all-MiniLM-L6-v2.onnx`, ~91 MB) **auto-download from the CDN on first run**
(default `DesktopAuxModels.DEFAULT_BASE_URL`, PR #3) and are verified against the
sha256/size pinned in `DesktopAuxModels`. A stock build needs nothing extra.

**Option A — your own host.** Override the base URL at build time:
```bash
./gradlew :desktopApp:packageDistributionForCurrentOS \
  -PauxModelBaseUrl=https://your.cdn/localagent/aux-models/v1.0.0
```

**Option B — per machine (air-gapped).** Drop the files into `<app-data>/models/` or point
env overrides at them:
```bash
export LOCALAGENT_CLASSIFIER_ONNX=/abs/path/preflight_memory_shared_v1.0.0.onnx
export LOCALAGENT_EMBEDDER_ONNX=/abs/path/all-MiniLM-L6-v2.onnx
```

> **If the aux models are absent (CDN unreachable + no override), search silently
> under-fires** — the classifier no-ops, so most queries won't trigger a web search (an
> explicit `web search …` command still works).

### Desktop runtime env vars (deployment, not build)

| Var | Purpose |
|---|---|
| `LOCALAGENT_LLAMA_SERVER` / `LOCALAGENT_LLAMA_SERVER_VARIANT` | pin a prebuilt server binary / force cpu\|vulkan\|auto |
| `LOCALAGENT_CLASSIFIER_ONNX` / `LOCALAGENT_EMBEDDER_ONNX` | Option B aux-model paths |
| `LOCALAGENT_VOSK_MODEL` / `LOCALAGENT_PIPER_BINARY` | pre-staged STT/TTS assets |
| `LOCALAGENT_GATEWAY_URL` / `LOCALAGENT_SUBSCRIPTION_PORTAL_URL` | Secure Gateway relay + billing portal (anywhere-access) |
| `LOCALAGENT_KEYSTORE_PASSWORD` | password for the encrypted PKCS#12 secret store |
| `LOCALAGENT_HEADLESS=1` | start the background runtime without a window (see `DESKTOP_PACKAGING.md` → headless deployment) |

The **Brave Search key** is entered per-user via Settings (BYOK); not baked into the build.

## A5. Desktop install & smoke test (per OS)

1. **Install:**
   - Linux `.deb` → `sudo dpkg -i local-agent_*.deb` (→ `/opt/local-agent/bin/LocalAgent`); `.rpm` → `sudo rpm -i …`.
   - macOS `.dmg`/`.pkg` → `/Applications/LocalAgent.app`.
   - Windows `.msi`/`.exe` → per-user `%LOCALAPPDATA%\LocalAgent\LocalAgent.exe`.
2. **First-run downloads:** launch the GUI; it pulls the llama-server binary + GGUF (+ ONNX
   aux models if Option A). Multi-GB — be patient.
3. **Functional smoke:**
   - **Chat:** plain prompt → streamed answer.
   - **Search:** "what's the weather in Toronto" → a search chip + grounded answer. (If
     search never fires, the aux models are missing — see §A4.)
   - **Voice (optional):** toggle mic (dictation); trigger read-aloud on a reply.
   - **Headless:** quit the GUI, then `LOCALAGENT_HEADLESS=1 <path>/LocalAgent` → tray
     (graphical) or windowless (server). Stop with tray **Shut down** or Ctrl-C/SIGTERM.
4. **Identity:** **About** → version `1.0.0` + expected git describe (no `-dirty`).
5. **Trust (signed builds):** macOS launches with no "unidentified developer" prompt; Windows shows the real publisher in UAC.

---

# Part B — Mobile (Android)

One build host (Part 0 prerequisites + Android SDK API 36). Target device: **Pixel 7 /
Android 16**; the app is **ARM64-only** (`abiFilters += "arm64-v8a"`).

## B1. Pre-build setup

### B1a. `secrets.properties` (signing) — REQUIRED for a distribution build

`android-app/secrets.properties` (next to `settings.gradle.kts`, gitignored) holds signing
config. Copy the template and fill in the release keystore:

```bash
cd <repo>/android-app
cp secrets.properties.example secrets.properties
```

Release-relevant keys (`BRAVE_DEV_KEY` is **debug-only** — release ships it empty and uses
BYOK, see §B4; the old `HF_AUTH_TOKEN` / `MODEL_*` entries were removed in PR #22 — all
models download from the public R2 CDN):

```properties
RELEASE_STORE_FILE=/abs/path/to/upload-keystore.jks
RELEASE_STORE_PASSWORD=…
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=…
```

> **`assembleRelease` / `bundleRelease` now FAIL FAST at configuration time when
> `RELEASE_STORE_FILE` is empty** (security PR #13). This prevents a distribution artifact
> from being silently signed with the public Android debug keystore. The debug-keystore
> fallback is retained **only** for `installRelease` (local on-device verification).

Create an upload keystore once (keep it + its passwords safe and OUT of the repo):

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias upload
```

### B1b. Firebase (optional)

Telemetry/crash reporting is gated on `androidApp/google-services.json` being present
(gitignored). To enable it, add the app's `applicationId` (`com.contextsolutions.localagent`)
in the Firebase console and drop the generated `google-services.json` into `androidApp/`.
**Absent → the google-services plugin isn't applied and analytics/Crashlytics degrade to
no-op** (the app still launches; #62). The `package_name` in the JSON must match the
release `applicationId` exactly (no `.debug` suffix on release).

## B2. Build

```bash
cd <repo>/android-app

# Play distribution artifact (App Bundle):
./gradlew :androidApp:bundleRelease
# → androidApp/build/outputs/bundle/release/androidApp-release.aab

# Direct-distribution / sideload artifact (APK):
./gradlew :androidApp:assembleRelease
# → androidApp/build/outputs/apk/release/androidApp-release.apk

# Local on-device verification (installs the minified release to a connected device;
# debug-keystore fallback is OK here even without RELEASE_STORE_FILE):
./gradlew :androidApp:installRelease
```

Release build characteristics:
- **R8 minify + resource shrink are ON** (`isMinifyEnabled` / `isShrinkResources`), using
  `proguard-android-optimize.txt` + `androidApp/proguard-rules.pro`. The keep/dontwarn rules
  for the reflective/JNI/Jackson/JNA paths (litert, LiteRT-LM, Room, Secure Gateway SDK,
  lazysodium/JNA) are load-bearing — see #70.
- `applicationId = com.contextsolutions.localagent` (release has **no `.debug` suffix**, so
  it installs alongside a debug build).
- `versionCode` = git commit timestamp (monotonic); `versionName` = `1.0.0`;
  `BuildConfig.GIT_DESCRIBE` shows in About.
- `minSdk` / `targetSdk` / `compileSdk` = **36**; ARM64-only.
- 16 KB ELF page alignment is satisfied for every bundled native lib (libsodium /
  libjnidispatch / the litert/tflite set), required by Play for API 35+ (#55).

## B3. Sign & distribute

### Option 1 — Google Play (AAB + Play App Signing) — recommended

1. The `bundleRelease` AAB is signed with your **upload key** (from §B1a).
2. In the Play Console, enroll the app in **Play App Signing** (Google holds the real app
   signing key; you sign uploads with the upload key).
3. Upload `androidApp-release.aab` to a track — start with **Internal testing / Closed beta**
   (the M7 path) before Production.
4. Play requires a **strictly increasing `versionCode`** — the git-commit-timestamp scheme
   guarantees this as long as you build from successive tagged commits.
5. `targetSdk = 36` meets Play's current target-API requirement.

### Option 2 — Direct APK (sideload / MDM)

Distribute the signed `androidApp-release.apk` directly (download link, MDM push, or
`adb install`). Users must allow "install from unknown sources". This APK is signed with the
key in `secrets.properties` (a real upload/OV key for distribution; a debug-keystore-signed
APK is **local verification only** and must never be shipped).

## B4. Mobile model & secret provisioning

The APK ships the app + small bundled assets only — **no models**. On first launch the
**Download screen gate** fetches all required models into `filesDir/models/` and unlocks chat
only when `ModelInventory.allRequiredPresent()` (Gemma + classifier + embedder).

| Artifact | Source | Auth |
|---|---|---|
| **Gemma 4 E2B** (LiteRT-LM, ~2.58 GB) | R2 CDN (`gemma-4-E2B-it.litertlm`, pinned in `ModelInventory`) | **none** (PR #22, moved off the gated HF repo) |
| **Pre-flight classifier** (`…_int8.tflite`, ~67.7 MB) | R2 CDN (`AndroidAuxModels`) | none |
| **MiniLM embedder** (`…_int8.tflite`, ~23.5 MB) | R2 CDN (`AndroidAuxModels`) | none |

Other per-user secrets (never baked into the build):
- **Brave Search key** — BYOK via **Settings** (chat search no-fires until set; default OFF, PR #22).
- **Relay/anywhere-access** — paired by scanning the desktop's relay QR; the account secret
  lands in `SecureStorage` (the phone never holds a subscription of its own).

> **QA pre-staging (optional).** To skip the multi-GB first-run download on a test device,
> push the models once (one file per `run-as cp`; see `CLAUDE.md` → "Build & run" for the
> exact `adb push` recipe for Gemma + the two aux `.tflite`).

## B5. Mobile install & smoke test

1. **Install:** `./gradlew :androidApp:installRelease`, or `adb install -r
   androidApp/build/outputs/apk/release/androidApp-release.apk` (or install from a Play
   test track).
2. **Onboarding:** language (English at launch) → privacy disclosure → country (PR #22
   removed the HF-token/Brave-key/telemetry steps) → the Download screen pulls Gemma +
   classifier + embedder from the R2 CDN (watch progress).
3. **Functional smoke** (exercise on the **signed release** — debug never runs R8, so
   R8-only breakage surfaces only here; #70):
   - **Chat:** plain prompt → streamed answer.
   - **Search:** set the Brave key in Settings, ask something current → search chip + grounded answer.
   - **Voice (optional):** dictation toggle; read-aloud on a reply.
   - **Relay pairing (optional):** scan the desktop's relay QR → chat + sync over the gateway.
     (Pairing exercises the JNA/lazysodium/Jackson paths most sensitive to R8 — verify it.)
4. **Identity:** **About** → version `1.0.0` + expected git describe (no `-dirty`).
5. **Telemetry (if Firebase enabled):** confirm the consent screen appears; with no
   `google-services.json`, confirm the app still launches (no-op telemetry).

---

# Part C — Release checklist (both platforms)

**Common**
- [ ] §0a Secure Gateway SDK (`:java` + `:android`, 0.2.2) published to mavenLocal on every host.
- [ ] §0c clean checkout of `vX.Y.Z`, `git status` clean; `appVersionName` confirmed.

**Desktop**
- [ ] §A4 aux-model strategy decided (default CDN / Option A URL / Option B files).
- [ ] **Linux:** `.deb` + `.rpm` built; `createDistributable` smoke passed.
- [ ] **macOS:** `.dmg` + `.pkg` built, **signed + notarized + stapled**; `spctl` passes.
- [ ] **Windows:** `.msi` + `.exe` built and **signtool-signed**; `signtool verify` passes.
- [ ] §A5 install + first-run + chat/search/voice/headless smoke passed per OS.
- [ ] About shows the right version/git per OS.
- [ ] Known gaps acknowledged: Linux unsigned; desktop R8 deferred.

**Mobile**
- [ ] §B1a `RELEASE_STORE_FILE` (real upload key) set in `secrets.properties`; `assembleRelease`/`bundleRelease` no longer fail fast.
- [ ] `bundleRelease` AAB (Play) and/or `assembleRelease` APK (direct) built **signed**.
- [ ] §B5 smoke passed **on the signed release**, including relay pairing (R8 exercise).
- [ ] `versionCode` increased vs. the previously shipped build (Play requirement).
- [ ] `google-services.json` present if telemetry is wanted (or its absence is intentional).
- [ ] AAB uploaded to the chosen Play track (Internal/Closed first), or APK distributed.

- [ ] Installers/artifacts collected for distribution.

---

# References

- `docs/DESKTOP_PACKAGING.md` — desktop native-runtime strategy, GPU opt-in, headless/service
  deployment + the systemd/launchd templates in `android-app/desktopApp/packaging/`.
- `.github/workflows/desktop-package.yml` — the tag-triggered desktop CI matrix (same gradle
  tasks). **There is no Android release CI yet — mobile is a local build.**
- `docs/ANYWHERE_ACCESS_PLAN.md` — Secure Gateway relay + subscription (`LOCALAGENT_GATEWAY_URL`,
  the `com.securegateway:*` SDK, mobile pairing).
- `CLAUDE.md` → "Build & run" — dev install commands, model pre-staging (`adb push`), wireless
  adb, and the R8/signing invariants (#70), Firebase fail-safe (#62), secrets fail-fast (#63).
- Signing DSL: JetBrains Compose Multiplatform "Signing and notarization on macOS"; Kotlin
  "Native distributions" docs; Android "Sign your app" / "Play App Signing".
