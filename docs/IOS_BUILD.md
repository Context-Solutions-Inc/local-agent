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

**Added in Phase 2** (see [`CLAUDE.md`](../CLAUDE.md) invariant #78 and the sections
below for the one-time Xcode setup each needs):

- **Voice I/O** — read-aloud via `AVSpeechSynthesizer` (`IosChatSpeaker`) + mic dictation
  via `SFSpeechRecognizer`/`AVAudioEngine` (`IosSpeechDictation`, prefer-on-device). Pure
  Kotlin/Native (no Swift bridge); needs the two Info.plist usage-description keys.
- **On-device classifier + embedder** — the pre-flight/memory classifier + MiniLM embedder
  run the FP32 `.onnx` models on **ONNX Runtime** via a Swift bridge (`OnnxRuntimeBridge`
  → `NativeClassifierBridge`/`NativeEmbedderBridge` → `OnnxIosClassifierEngine`/
  `OnnxIosEmbedderEngine`). Lazily downloaded per enabled feature (`IosAuxModelWarmer`).
- **Web search** — the Brave verticals fire once the classifier is real; the user sets a
  Brave key + toggles search on in Settings (BYOK). Real `search_defaults.json` +
  `locations.json` are bundled.
- **Memory** — works once the embedder `.onnx` is present (memory creation defaults ON).

**Added in Phase 3 (PR #44):**

- **Image input + vision** — take a photo with the **in-app camera**
  (`UIImagePickerController`, `sourceType = .camera`; needs the `NSCameraUsageDescription`
  Info.plist key) and Gemma 4 E2B describes it on-device. The **vision encoder runs on
  CPU/XNNPack** (`visionBackend = .cpu()`); the text LLM stays on Metal. See
  [`CLAUDE.md`](../CLAUDE.md) invariant #79 and the "Vision" gotcha below.

**Added in Phase 4 (PR #45) — relay / desktop-pairing:**

- **Pair with a subscribed desktop over the Secure Gateway E2EE relay** (scan the desktop's
  QR) and tunnel **chat + sync** to it; falls back to the on-device model when the link is
  down. Foreground-only (like Android). The phone pairs — it never subscribes.
- **Mobile Jobs controls over the relay** — run-now ▶, cancel ⏹, the "run job …" chat
  command, and the re-sync icon. (Create/edit/delete/scheduling stay desktop-only.)
- Bridges the **native Swift `SecureGatewaySDK`** (no Kotlin/Native artifact exists) via a
  callback-shaped `NativeRelayBridge`, mirroring `NativeLlmBridge`. QR scan is a Swift
  AVFoundation `NativeQrScanner` (reuses `NSCameraUsageDescription`). See
  [`CLAUDE.md`](../CLAUDE.md) invariant #80, §8 below, and
  [`ANYWHERE_ACCESS_PLAN.md`](ANYWHERE_ACCESS_PLAN.md).
- **Image-over-relay needs the relay `RELAY_MAX_MESSAGE_BYTES` raised 256 KiB → 1 MiB** — a
  ~768px JPEG is ~264 KiB on the wire (double-base64), just over the old cap. Relay server
  (Go) config only; no iOS/SDK change.

Deferred to follow-ups (no-op stubs this milestone):
APNs push-to-wake (the gateway has no push infra; foreground-only like Android), jobs-admin
(the phone is a remote controller, not an admin), iOS subscription purchase. The local
SQLite DB is **unkeyed** on iOS
(SQLCipher-on-iOS is deferred). Aux-model downloads are **size-verified only**
(SHA-256 verification is a follow-up, matching the Gemma downloader).
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

### 5. The ONNX Runtime Swift package + aux bridge (one-time, in Xcode) — Phase 2

The pre-flight classifier + MiniLM embedder run their FP32 `.onnx` models on ONNX
Runtime via a Swift bridge. In Xcode:

1. **File → Add Package Dependencies…** → `https://github.com/microsoft/onnxruntime-swift-package-manager`
   → add the **`onnxruntime`** library product to the `iosApp` target. **Prefer this prebuilt
   xcframework over a from-source pod** so ORT's vendored deps (protobuf/abseil/XNNPACK/nsync)
   stay symbol-hidden and don't collide with LiteRT-LM under the `-all_load` link (see the
   duplicate-symbol gotcha below).
   > ⚠️ The library **product** is `onnxruntime`, but the importable Swift **module** is
   > **`OnnxRuntimeBindings`** (the SwiftPM target re-exporting the binary's Objective-C API).
   > `OnnxRuntimeBridge.swift` therefore does `import OnnxRuntimeBindings` — `import onnxruntime`
   > fails with `No such module 'onnxruntime'`.
2. **Add `iosApp/iosApp/OnnxRuntimeBridge.swift` to the target** (Add Files to "iosApp"…).
   It conforms to both Kotlin protocols (`NativeClassifierBridge`/`NativeEmbedderBridge`)
   with two `ORTSession`s sharing one `ORTEnv`, CoreML EP + CPU fallback.

> The ORT Objective-C symbol names + the CoreML-EP call in `OnnxRuntimeBridge.swift`
> are a **scaffold** (version-sensitive; can only be finalized on-device in Xcode — CI
> is compile-only). The Kotlin bridge contract (pure-numeric, named IO
> `input_ids`/`attention_mask` int64; classifier outputs `preflight_logits`[1,3] /
> `presence_logits`[1,2] / `category_logits`[1,6]; embedder = single output [1,384]) is
> stable and matches the desktop `OnnxClassifierEngine`/`OnnxEmbedderEngine`.

The two `.onnx` models (~357 MB total: 266 MB classifier + 91 MB embedder) are **not
bundled** — `IosAuxModelWarmer` downloads them from the models CDN lazily, per enabled
feature (embedder when memory is on = default; classifier when web search is enabled),
into `<AppSupport>/models`. Absent model → `warmUp()` no-ops and the feature degrades.

### 6. Bundle resources: `vocab.txt` + search defaults (one-time, in Xcode) — Phase 2

The WordPiece `vocab.txt` (classifier/embedder tokenizer) and the search
`search_defaults.json` / `locations.json` ship **in the app bundle** (read via
`NSBundle` — `IosVocabLoader` / `IosResources`). Add all three (already staged next to
the Swift sources in `iosApp/iosApp/`) to the `iosApp` target's **Copy Bundle Resources**
build phase (Add Files to "iosApp"… → ensure "Copy items"/target membership). They're
byte-identical to the Android assets (`androidApp/src/main/assets/`). Missing → the
tokenizer falls back to a 5-token stub (classifier/embedder inert) and search uses empty
defaults, so this step is required for the Phase-2 features to work.

### 7. Info.plist permission keys (already committed)

`Info.plist` carries `NSMicrophoneUsageDescription` + `NSSpeechRecognitionUsageDescription`
for voice dictation (Phase 2) and `NSCameraUsageDescription` for in-app camera capture
(Phase 3, reused by the Phase-4 QR scanner) — iOS crashes the permission prompt / camera
access without them. No action needed unless you change the copy.

### 8. The Secure Gateway relay SDK + script sandboxing (one-time, in Xcode) — Phase 4

The relay client bridges the **native Swift `SecureGatewaySDK`** (there is no Kotlin/Native
securegateway artifact; `:core` is JVM-only). In Xcode → **File → Add Package Dependencies…**
→ **Add Local…** → select the sibling working tree `../secure-gateway/sdk/ios` → add the
**`SecureGatewaySDK`** product to the `iosApp` target. SwiftPM transitively pulls
`swift-sodium` + `swift-crypto`. (Use a local package during co-dev so both repos evolve in
lockstep; pin to a tag/commit — like the LiteRT-LM package — for CI/release once stable.)
The relay + QR bridges (`RelayBridge.swift`, `QRScannerBridge.swift`) are already committed
and members of the `iosApp` target.

**`ENABLE_USER_SCRIPT_SANDBOXING = NO` (required).** The "Build Kotlin framework" run-script
phase (`:ui:embedAndSignAppleFrameworkForXcode`) needs filesystem access outside the sandbox
and the Kotlin plugin refuses to run under it (*"Sandbox environment detected … not supported
so far"*). It's set to `NO` in the committed pbxproj; if Xcode's **"Update to recommended
settings"** flips it back to `YES`, set it to `NO` again (project → Build Settings → *User
Script Sandboxing*). The iOS **deployment target is 15.1** (the prebuilt onnxruntime
framework is built for 15.1).

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
6. **Voice (Phase 2):** grant mic + speech permission; toggle the mic → dictate (partials
   stream into the box, final commits); say "send" / "cancel" / "speaker off"; read-aloud
   fires at answer end and the Stop button truly silences it.
7. **Web search (Phase 2):** Settings → enter a Brave key + enable search → the 266 MB
   classifier `.onnx` downloads → ask a fresh-info question → it routes to search (vs. the
   pre-Phase-2 fall-through). Requires a relaunch after enabling for the aux download to
   kick (the warmer re-checks feature flags on the next entry to the main UI).
8. **Memory (Phase 2):** with memory on (default), the 91 MB embedder `.onnx` downloads in
   the background → state a fact, ask it back in a later turn → it's retrieved; the consent
   card appears.
9. **Resilience:** airplane-mode or kill mid aux-download → no crash, features degrade
   (`warmUp` returns null); the resumable download recovers via `Range` on the next kick.
   Confirm the `.onnx` files land in `<AppSupport>/models`, excluded from iCloud backup.
10. **Image + vision (Phase 3):** tap the image icon → the **in-app camera** opens
    (grant the one-time camera permission on first use) → take a photo → the staged chip
    renders it → ask "what's in this photo?" → the reply describes it (no
    `STABLEHLO_COMPOSITE` error; the log shows the vision encoder using an `xnnpack_cache`
    path). Also try an image-only turn and a text follow-up in the same thread; the sent
    bubble keeps the photo across relaunch. (Camera needs a physical device — the
    Simulator has none, so the button no-ops there.)
11. **Relay / pairing (Phase 4):** on a Stripe-subscribed desktop, Settings → **Pair Now**
    → scan the QR on the phone (Settings → Desktop Agent Connection → Scan) → the link dot
    goes UP; send a turn → answered by the **desktop** engine (logs show `[DesktopLink]`, not
    on-device); add a My List item on one side → it syncs to the other. **Disconnect** the
    relay → the next turn falls back to the **on-device GPU** (no "trouble processing" error).
    Background/foreground → reconnects without re-scanning. Jobs: run ▶ a job / cancel ⏹ / the
    re-sync icon all drive the desktop. **Image-over-relay** needs the relay
    `RELAY_MAX_MESSAGE_BYTES` at 1 MiB (else the ~264 KiB frame drops the socket).

## Architecture

| Concern | Lives in | Notes |
|---|---|---|
| Shared UI / navigation / chat / persistence | `:ui` commonMain (`ComposeApp` framework) | unchanged across platforms |
| On-device LLM | Swift `LiteRtBridge` → Kotlin `NativeLlmBridge` → `LiteRtIosInferenceEngine` | the `local` engine inside `RoutingInferenceEngine` |
| Classifier + embedder (Phase 2) | Swift `OnnxRuntimeBridge` → `NativeClassifierBridge`/`NativeEmbedderBridge` → `OnnxIos{Classifier,Embedder}Engine` | ONNX Runtime; models via `IosAuxModelStore`/`IosAuxModelWarmer` |
| Voice (Phase 2) | `IosChatSpeaker` (`AVSpeechSynthesizer`) + `IosSpeechDictation` (`SFSpeechRecognizer`) | pure Kotlin/Native, no Swift bridge |
| Relay / pairing (Phase 4) | Swift `RelayBridge` → Kotlin `NativeRelayBridge` → `IosRelayBytePipe`/`IosRelayBytePipeFactory` → shared `RelayBytePipe`/`LinkTransportProvider` seam | native `SecureGatewaySDK`; chat routes via `DesktopLinkInferenceEngine`, sync via `SyncController` |
| QR scan (Phase 4) | Swift `QRScannerBridge` (AVFoundation) → Kotlin `NativeQrScanner` | reuses `NSCameraUsageDescription` |
| Networking | `IosHttpEngineFactory` (Ktor Darwin) | Ollama/search |
| Secrets | `IosSecureStorage` (Keychain) | Ollama/Brave keys, relay pairing state + identity |
| Database | `IosDatabaseFactory` (`NativeSqliteDriver`, unkeyed) | |
| DI | `iosModule` + `IosEntryPointKt.doInitKoin(llmBridge, classifierBridge, embedderBridge, relayBridge, qrScanner)` | mirrors `androidModule` |

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
- **ONNX Runtime coexists with LiteRT-LM under `-all_load` — use the prebuilt xcframework
  (Phase 2).** The same LiteRT-LM `-all_load` that forces the dynamic framework also
  applies to ORT (linked at the app target for `OnnxRuntimeBridge.swift`). Both runtimes
  can vendor protobuf/abseil/XNNPACK/nsync, so a *from-source* ORT pod risks duplicate
  symbols with LiteRT-LM. The **prebuilt `onnxruntime-objc`/`-c` xcframework** keeps ORT's
  internal deps symbol-hidden inside the xcframework (not re-exported into the `-all_load`
  set), which is why §5 pins that distribution. Confirm at link time on-device (CI is
  compile-only and doesn't link the ORT app target).
- **The framework must link `libsqlite3` (`linkerOpts("-lsqlite3")` in the Gradle
  framework block).** SQLDelight's `NativeSqliteDriver` (via `co.touchlab:sqliter`)
  references system SQLite symbols; sqliter's cinterop linker opt doesn't propagate to
  the consumer framework, so without it the **framework** link fails with `Undefined
  symbols: _sqlite3_*`. (The app target also carries `-lsqlite3` in `OTHER_LDFLAGS` as
  a harmless belt-and-suspenders.)
- **`Info.plist` needs `CADisableMinimumFrameDurationOnPhone = true`.** Compose
  Multiplatform's `PlistSanityCheck` throws `IllegalStateException` on launch without
  it (it enables full refresh rate on ProMotion iPhones).
- **`ENABLE_USER_SCRIPT_SANDBOXING` must be `NO` (Phase 4).** The "Build Kotlin framework"
  run-script phase (`:ui:embedAndSignAppleFrameworkForXcode`) can't run under the script
  sandbox — it fails *"Sandbox environment detected (ENABLE_USER_SCRIPT_SANDBOXING = YES).
  It's not supported so far."* Xcode's **"Update to recommended settings"** turns it back on;
  set it to `NO` again. Standard for any Compose-Multiplatform iOS app.
- **The relay caps each WebSocket message at `RELAY_MAX_MESSAGE_BYTES` (Phase 4).** The
  default 256 KiB is too small for an **image** chat turn: the base64'd JPEG in the link
  frame is sealed + base64'd again into the E2EE envelope (~1.8× the JPEG), so a ~768px photo
  is ~264 KiB on the wire and the relay drops the connection (surfaces as `send failed …
  CryptoError error 2` / "Socket is not connected" on the phone — the crypto is fine, the
  socket closed). Raise the **relay server** env to `1048576`. No iOS/Swift-SDK change; text
  chat + sync are unaffected. After raising it, verify the desktop's `java.net.http.WebSocket`
  transport reassembles the multi-part receive (the `Transport` contract requires it).
- **Vision is ON (PR #44) but the vision encoder MUST use the CPU/XNNPack backend —
  NOT Metal.** `LiteRtBridge.load()` sets `visionBackend = .cpu()` while the text LLM
  stays on `.gpu` (Metal). Gemma 4 E2B's `.litertlm` pins the vision encoder/adapter to
  `section_backend_constraint: cpu` and its 1477-op SigLIP encoder is fully
  XNNPack-delegatable on iOS (LiteRT-LM #2370). Requesting the **Metal** vision backend
  instead routes to the compiled-model executor whose `STABLEHLO_COMPOSITE` op is not
  registered in the iOS dylib → `Node number … (STABLEHLO_COMPOSITE) failed to prepare`
  → `Failed to create conversation` on *every* turn (this is why PR #41 shipped vision
  OFF). **E4B vision stays broken on iOS** (its encoder isn't fully XNNPack-delegatable —
  genuinely blocked upstream, #2370 open). LiteRT-LM stays pinned to the v0.13.1 commit
  (no upstream fix: v0.14.0-alpha.0 doesn't register the op). **Benign:**
  `libLiteRtTopKMetalSampler.dylib` isn't bundled, so the top-K sampler logs
  `Metal sampler not available` and falls back to the CPU C API — correctness-neutral.
  See invariant #79.
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
