# Building & testing the app on iOS (iPhone)

How to build, run, and test the iOS app (PR #41) — written for a **physical
iPhone** (on-device LiteRT-LM needs real Metal hardware; see the Simulator note
below). The iOS app reuses the shared KMP core (`:shared`) and the shared
Compose-Multiplatform UI (`:ui`, exported as the `ComposeApp` framework); only a
thin SwiftUI shell + the LiteRT-LM Swift engine bridge live in `iosApp/`.

The Gradle project root is **`android-app/`** (run all `./gradlew` commands from
there). The Xcode project is **`android-app/iosApp/iosApp.xcodeproj`**.

## What works in this milestone

- On-device chat with **Gemma 4 E2B via the LiteRT-LM Swift API (Metal)**, streamed.
- Conversation history persisted (SQLDelight native driver).
- Settings, navigation, the shared UI.
- Optional remote chat (Settings → Remote LLM) over the Darwin HTTP engine.
- Clock **timers** (and alarms) fire OS notifications via `UNUserNotificationCenter`
  (`IosAlarmScheduler`) — delivered foreground, backgrounded, or locked.

Deferred to follow-ups (no-op stubs this milestone): voice (STT/TTS), image
input, web search, on-device memory/embeddings, relay/desktop-pairing, jobs.
The local SQLite DB is **unkeyed** on iOS (SQLCipher-on-iOS is deferred).
**Recurring alarms** are best-effort: iOS delivers the scheduled notification but
the next occurrence re-arms only on the app's next launch (`ClockService.rearmAll`);
one-shot timers are fully correct. iOS can't ring an alarm continuously like Android.

## Prerequisites

### 1. JDK 17 (Temurin)
Same as the desktop build — see [`MACOS_BUILD.md`](MACOS_BUILD.md). `export
JAVA_HOME="$(/usr/libexec/java_home -v 17)"`.

### 2. Xcode + an Apple Developer team
Xcode 15+ with the iOS SDK. A signing team (a **free personal team** is enough
for installing on your own device) configured in Xcode → the app target →
Signing & Capabilities.

### 3. GitHub Packages auth
The `:shared` build resolves the relay SDK during configuration, so the same
`gpr.user`/`gpr.key` (classic PAT, `repo` + `read:packages`) the desktop build
needs applies — see [`MACOS_BUILD.md`](MACOS_BUILD.md) §2.

### 4. The LiteRT-LM Swift package (one-time, in Xcode)
The on-device engine uses the official Swift package. In Xcode → **File → Add
Package Dependencies…** → `https://github.com/google-ai-edge/LiteRT-LM` →
**Dependency Rule = Commit `a0afb5a56acd106b23a2b2385b8469834dc268c0`** (the
commit the `v0.13.1` tag points at) → add the `LiteRTLM` library to the `iosApp`
target. (The Kotlin side never references it — it's consumed only by
`iosApp/iosApp/LiteRtBridge.swift`.)

> ⚠️ **Pin by COMMIT, not by version, and never "Branch: `main`".** Two SwiftPM
> traps, both with confusing errors:
>
> 1. The `LiteRTLM` target uses `.unsafeFlags(["-Xlinker", "-all_load"])`. SwiftPM
>    **forbids depending on a product with unsafe flags via a *version*
>    requirement**, so an "Exact Version `0.13.1`" pin fails with:
>    ```
>    The package product 'LiteRTLM' cannot be used as a dependency of this
>    target because it uses unsafe build flags.
>    ```
>    A **commit/revision** pin is exempt — use the v0.13.1 commit above.
> 2. The binary `CLiteRTLM.xcframework` is published per release (v0.13.1 bundles
>    the v0.13.0 binary). `main`'s Swift sources call newer C symbols the released
>    binary doesn't export, so the package's *own* `swift/Engine.swift` won't
>    compile:
>    ```
>    Cannot find 'litert_lm_sampler_params_create' in scope
>    Cannot find 'litert_lm_conversation_config_set_stream_tool_calls' in scope
>    ```
>    Pinning the v0.13.1 **commit** avoids both — it's a revision (rule 1) *and*
>    the exact v0.13.1 sources (rule 2).
>
> If either error appears: Xcode → **Package Dependencies** → select LiteRT-LM →
> **Dependency Rule = Commit** → `a0afb5a56acd106b23a2b2385b8469834dc268c0` →
> **File → Packages → Reset Package Caches** → rebuild.
>
> `LiteRtBridge.swift` is written against the v0.13.1 Swift API
> (`EngineConfig`/`Engine.initialize()`/`ConversationConfig`/`SamplerConfig`/
> `Message`); a different commit may shift those signatures.

## Build & run

1. Open `android-app/iosApp/iosApp.xcodeproj` in Xcode.
2. Select your team under Signing & Capabilities; set a unique bundle id.
3. Pick your connected iPhone (or an iOS Simulator — UI/remote only, see below).
4. Run (⌘R).

The Xcode build runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` (a Run
Script build phase) to build + embed the `ComposeApp.framework`, then compiles
the Swift shell against it.

**First launch** downloads the Gemma `.litertlm` (~2.58 GB) into the app's
Application Support dir with a progress screen; chat unlocks when the download
completes (verified by byte size; the file is excluded from iCloud backup).

CLI build of the framework (what CI gates on — no Xcode):
```bash
cd android-app
./gradlew :shared:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosSimulatorArm64
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64
```
Full Simulator app build:
```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 15' build
```

## ⚠️ On-device LLM does NOT run on the iOS Simulator

LiteRT-LM inference works on a **physical iPhone** (and macOS) only — on the iOS
Simulator CPU inference crashes and Metal is unavailable
([google-ai-edge/LiteRT-LM#2504](https://github.com/google-ai-edge/LiteRT-LM/issues/2504)).
On the Simulator, use the UI + the **remote** chat path (Settings → Remote LLM →
point at a reachable Ollama / OpenAI-compatible server) to exercise chat. CI is
therefore build-only (compile + framework link).

## Test

```bash
cd android-app
# Shared-core iOS unit suite (Kotlin/Native):
./gradlew :shared:iosSimulatorArm64Test
```
CI runs the compile + framework-link gates on `macos-latest` via
[`.github/workflows/ios-test.yml`](../.github/workflows/ios-test.yml).

### Acceptance (physical iPhone)
1. App launches → onboarding → model-download gate fetches Gemma; chat unlocks.
2. Send a message → Gemma 4 E2B runs **on-device via Metal**, reply streams;
   the load banner shows GPU/CPU.
3. Relaunch → conversation history persists.
4. Cancel mid-generation stops promptly.
5. Settings opens; configuring a remote Ollama routes there instead of local.

## Architecture

| Concern | Lives in | Notes |
|---|---|---|
| Shared UI / navigation / chat / persistence | `:ui` commonMain (`ComposeApp` framework) | unchanged across platforms |
| On-device LLM | Swift `LiteRtBridge` → Kotlin `NativeLlmBridge` → `LiteRtIosInferenceEngine` | the `local` engine inside `RoutingInferenceEngine` |
| Networking | `IosHttpEngineFactory` (Ktor Darwin) | Ollama/search/link |
| Secrets | `IosSecureStorage` (Keychain) | Ollama/Brave keys |
| Database | `IosDatabaseFactory` (`NativeSqliteDriver`, unkeyed) | |
| DI | `iosModule` + `IosEntryPointKt.doInitKoin(bridge)` | mirrors `androidModule` |

## iOS gotchas

- **iOS toolchain artifacts are pinned** in `gradle/verification-metadata.xml`
  (Kotlin/Native prebuilt, Skiko iOS, Ktor-Darwin). A dependency bump that pulls
  a new iOS native fails verification and names it — regenerate per the recipe in
  `CLAUDE.md`. (Generate iOS entries on macOS: the natives are macOS-resolvable.)
- **iOS metadata regen MUST include the resources task, not just compile/link.**
  The Xcode build (`embedAndSignAppleFrameworkForXcode`) resolves Compose-Multiplatform
  `*.kotlin_resources.zip` artifacts via `:ui:iosArm64ResolveResourcesFromDependencies`
  — a task that `compileKotlinIosArm64`/`linkDebugFrameworkIosArm64` do **not** trigger.
  Regenerating with only the compile/link tasks silently misses those zips, and the
  Xcode build then fails `Dependency verification failed for configuration
  ':ui:iosArm64CompileKlibraries'` naming the `.kotlin_resources.zip` files (e.g.
  `koin-compose`, `multiplatform-markdown-renderer`). It also surfaces as a bogus
  config-cache serialization error on that resources task (`org.gradle.configuration-cache=true`
  is global) — the verification failure is the real cause. Regenerate including all
  three iOS variants:
  ```bash
  ./gradlew --write-verification-metadata sha256 --no-configuration-cache \
    :ui:iosArm64ResolveResourcesFromDependencies \
    :ui:iosSimulatorArm64ResolveResourcesFromDependencies \
    :ui:iosX64ResolveResourcesFromDependencies
  ```
- **SwiftUI Previews are disabled (`ENABLE_PREVIEWS = NO`).** The host app only
  wraps a Kotlin-provided `UIViewController`, so previews can't render anyway, and
  Xcode 16's preview "blank injection" dylib (`__preview.dylib`) fails to link
  against the `ComposeApp` framework (`Command Ld failed … __preview.dylib`).
  Leave previews off; the main app dylib links fine.
- **`Assets.xcassets` must sit in the inner `iosApp/iosApp/` dir** (next to the
  Swift sources / `Info.plist`), not the outer `iosApp/`. The pbxproj group is
  `path = iosApp`, so a catalog one level up is invisible to actool → build fails
  *"None of the input catalogs contained … an app icon set … named 'AppIcon'"*.
- **`ComposeApp` is a DYNAMIC framework (`isStatic = false`) — do not make it static.**
  The LiteRT-LM SwiftPM package forces `-Xlinker -all_load` (its `unsafeFlags`) onto
  the whole app link. Skiko bundles its C libs (libjpeg/libicu/libpng/libwebp/libdng_sdk)
  **twice** inside the framework archive, so with a *static* framework `-all_load`
  force-loads both copies → ~15.9k `duplicate symbol` link errors. A dynamic framework
  resolves Skiko internally at framework-link time, beyond `-all_load`'s reach. Set in
  `ui/build.gradle.kts`.
- **The framework must link `libsqlite3` (`linkerOpts("-lsqlite3")` in the Gradle
  framework block).** SQLDelight's `NativeSqliteDriver` (via `co.touchlab:sqliter`)
  references system SQLite symbols; sqliter's cinterop linker opt doesn't propagate to
  the consumer framework, so without it the **framework** link fails with `Undefined
  symbols: _sqlite3_*`. (The app target also carries `-lsqlite3` in `OTHER_LDFLAGS` as
  a harmless belt-and-suspenders.)
- **`Info.plist` needs `CADisableMinimumFrameDurationOnPhone = true`.** Compose
  Multiplatform's `PlistSanityCheck` throws `IllegalStateException` on launch without
  it (it enables full refresh rate on ProMotion iPhones).
- **Vision is OFF on iOS (`enableVision = false` in `iosModule`).** Enabling the
  LiteRT-LM vision encoder makes `createConversation` fail on the iOS Metal backend
  (v0.13.1): `Node number … (STABLEHLO_COMPOSITE) failed to prepare` →
  `Failed to create conversation` on *every* chat turn (surfaces in the UI as
  "I had trouble processing that request"). The engine + chat-session config both
  request text-only. Image input is a no-op this milestone anyway; re-enabling vision
  needs a LiteRT-LM build whose vision graph prepares on Metal.
- **Timers/alarms need notification authorization + a foreground delegate.**
  `IosAlarmScheduler` requests `UNUserNotificationCenter` authorization on first use
  and sets a `UNUserNotificationCenterDelegate` returning banner+sound — without the
  delegate, iOS silently suppresses a timer notification while the app is foregrounded
  (which looks like "timers don't fire"). If a user denies the permission prompt,
  notifications are still scheduled but the OS won't display them (re-enable in iOS
  Settings → the app → Notifications).
- **App data / model** live in the app sandbox's Application Support dir; delete
  the app to force a clean first-run.
- **LiteRT-LM Swift symbol names** in `LiteRtBridge.swift` are a scaffold against
  the documented API — adjust to the package's current surface when wiring
  on-device (the bridge contract on the Kotlin side is stable).
