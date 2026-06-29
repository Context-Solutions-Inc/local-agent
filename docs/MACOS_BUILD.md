# Building & testing the desktop app on macOS

How to build, run, and test the Compose Desktop app (`:desktopApp`) on macOS —
written for **Apple Silicon (arm64)**, the architecture the team develops on.
For native installer packaging across all three OSes see
[`DESKTOP_PACKAGING.md`](DESKTOP_PACKAGING.md); for the headless service deploy
see its "Headless / standalone deployment" section.

The Gradle project root is **`android-app/`** (run all `./gradlew` commands from
there).

## Prerequisites

### 1. JDK 17 — use Temurin, not Homebrew

Xcode does **not** ship a JDK. The toolchain needs **JDK 17** (`jvmToolchain(17)`
in `desktopApp/build.gradle.kts`; jpackage for `.dmg`/`.pkg` needs 14+).

**Use the Temurin (Adoptium) distribution** — it's what CI uses and what
jpackage expects. The Homebrew `openjdk@17` works for compiling and
`:shared:desktopTest`, but Compose Desktop's `checkRuntime` task **rejects it
for packaging** (`createDistributable` / `packageDistributionForCurrentOS`) —
[compose-multiplatform#3107](https://github.com/JetBrains/compose-multiplatform/issues/3107):

```
Homebrew's JDK distribution may cause issues with packaging.
```

Install Temurin 17:

```bash
brew install --cask temurin@17          # installs to /Library/Java/JavaVirtualMachines
# Put this in ~/.zshrc:
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
java -version   # expect: openjdk version "17.x" ... Temurin
```

(Amazon Corretto 17 works too.) If you must use the Homebrew JDK, bypass the
vendor check at your own risk by adding
`compose.desktop.packaging.checkJdkVendor=false` to `~/.gradle/gradle.properties`
(or passing `-Pcompose.desktop.packaging.checkJdkVendor=false`). This is **not**
set in the repo's `gradle.properties` on purpose — CI and the recommended local
setup use a real vendor JDK.

### 2. GitHub Packages auth (required)

The relay SDK (`com.contextsolutions.securegateway:{core,java,android}`)
resolves from secure-gateway's **private** GitHub Packages registry
(`android-app/settings.gradle.kts`). Every `:shared` / `:desktopApp` build needs
`read:packages` auth or dependency resolution fails.

Create a **classic** Personal Access Token with scopes `repo` + `read:packages`
(fine-grained PATs do **not** reliably read the Maven registry), then add to
`~/.gradle/gradle.properties` (not the repo):

```properties
gpr.user=<your-github-username>
gpr.key=<classic-PAT>
```

Alternatively export `GITHUB_ACTOR` / `GITHUB_TOKEN` in your shell.

### 3. Xcode command-line tools (optional)

Only needed for code-signing/notarization (`security`, `codesign`) — unsigned
builds run fine locally and in CI. Installer packaging (`.dmg`) uses the
built-in `hdiutil`.

## Build & run

```bash
cd android-app

# Launch the app (windowed; -Dlocalagent.debug=true full diagnostic logging).
# First run downloads into ~/Library/Application Support/LocalAgent/:
#   - Gemma GGUF (~3.1 GB) + vision mmproj
#   - llama-server (macos-arm64, Metal) prebuilt binary
#   - ONNX classifier + embedder aux models, Vosk STT (background)
./gradlew :desktopApp:run

# App-image smoke (no installer tooling needed):
./gradlew :desktopApp:createDistributable
open desktopApp/build/compose/binaries/main/app/LocalAgent.app

# Full native installers (.dmg + .pkg):
./gradlew :desktopApp:packageDistributionForCurrentOS
# Outputs: desktopApp/build/compose/binaries/main/{dmg,pkg}/
```

## Test

```bash
cd android-app

# Desktop unit suite (shared/src/desktopTest, ~20 classes).
# OnnxEngineParityTest self-skips if the .onnx models aren't present locally —
# that's expected and not a failure.
./gradlew :shared:desktopTest
```

CI runs the same `:shared:desktopTest` + `:desktopApp:createDistributable` smoke
on every PR via [`.github/workflows/desktop-test.yml`](../.github/workflows/desktop-test.yml)
(macos-latest + ubuntu-latest). The slower installer matrix
(`desktop-package.yml`) stays tag/dispatch-only.

## Useful env knobs

| Variable | Effect |
|---|---|
| `LOCALAGENT_LLAMA_SERVER_VARIANT` | `cpu` \| `vulkan` \| `auto` (default `auto`; macOS uses the Metal-capable archive, CPU fallback on start failure). |
| `LOCALAGENT_LLAMA_SERVER` | Path to an existing `llama-server` binary (skips the download). |
| `LOCALAGENT_HEADLESS=1` | Run as a background service without opening the main window (see `DESKTOP_PACKAGING.md`; launchd template at `desktopApp/packaging/com.contextsolutions.localagent.plist`). |
| `DI_CHECK=1` | Smoke-test the Koin graph and exit without opening a window. |
| `LOCALAGENT_CLASSIFIER_ONNX` / `LOCALAGENT_EMBEDDER_ONNX` | Use a local ONNX model instead of downloading. |

## macOS gotchas

- **Apple Silicon natives are already pinned** in
  `gradle/verification-metadata.xml` (`skiko-awt-runtime-macos-arm64-*`,
  llama-server `macos-arm64`), so dependency verification passes out of the box.
  A dependency bump that pulls a *new* macOS native will fail verification and
  name the missing artifact — add its SHA-256 per the "Regenerate after any
  dependency bump" recipe in `CLAUDE.md`.
- **Metal vs CPU is automatic** — no Vulkan on macOS; the single Metal-capable
  `llama-server` archive serves GPU (`-ngl`) with silent CPU fallback.
- **App data** lives in `~/Library/Application Support/LocalAgent/` (models,
  SQLite DB, prefs, llama-server cache). Delete it to force a clean first-run.
- **Single-instance lock** (`<app-data>/.instance.lock`) — a headless service
  and a GUI run can't run simultaneously.
- **Signing/notarization** is gated on `MAC_SIGN_IDENTITY` (+ `NOTARIZATION_*`)
  env vars in `desktopApp/build.gradle.kts`; off by default.
- **Vosk STT (`vosk_recognizer_set_grm` symbol)** — the `com.alphacephei:vosk:0.3.45`
  jar ships a stale macOS `libvosk.dylib` missing the `vosk_recognizer_set_grm`
  symbol, which JNA's eager `Native.register` crashes on at load (dictation dies
  with `Error looking up function 'vosk_recognizer_set_grm'`). Worked around by a
  shadow `org.vosk.LibVosk` (`shared/src/desktopMain/java/org/vosk/LibVosk.java`)
  that omits that one unused binding. Guarded by `LibVoskShadowTest`. See
  [`VOICE_IO.md`](VOICE_IO.md).
