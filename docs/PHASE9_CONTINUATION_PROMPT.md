# Continuation prompt — Desktop port Phase 9, increments 8c → 8d → 9

Paste everything below the line into a fresh session to resume the desktop-port UI cutover.

---

Continue the desktop port of this app (Kotlin Multiplatform, Compose Multiplatform + llama.cpp).
Branch: `feature/desktop-cmp`. Phases 0–8 are DONE/committed. **Phase 9 (UI cutover, screen-by-screen, Chat LAST) is IN PROGRESS**: increments 1–7 + 8a + 8b are committed and green. Your job is to finish **8c, 8d, and increment 9 (release prep)** — the Chat cutover and the final shell-shrink + version bump.

## First, read these (in order)
1. `docs/DESKTOP_PORT_PLAN.md` — full phased plan + per-increment status logs. The **Phase 9 section** (near the end) has the per-increment detail; **§Increment 8** spells out the Chat sub-increment plan (8a/8b done; **8c and 8d are written out there verbatim** — follow them).
2. Auto-memory `project_desktop_port.md` (via `MEMORY.md`) — running status + every gotcha. The Phase-9 entries (incs 1–8b) and the 8c/8d/9 TODO bullets are the source of truth for what's left.
3. `CLAUDE.md` (auto-loaded) — hard invariants. Phase-9-relevant: #34/#36/#41 (prompt assembly + markdown-render gate, now in `:ui` `MarkdownMath`), #42 (voice — `ChatSpeaker`/`Dictation` seams), #39 (vision — `FilePicker`/`ImagePreprocessor` seams), #22 (`LifecycleResumeEffect`), #26 (no `liveRegion` on streaming bubble).

Verify branch state first: `git -C /home/lawrenceley/src/mobile-agent log --oneline -3` → HEAD should be the Phase-9 status doc commit (`deeea0d` or later) on `feature/desktop-cmp`. Nothing is pushed.

## Where things stand (recap)
- `:ui` (Compose MP, android + `jvm("desktop")`) now hosts the migrated screens: **Todo, Settings, SearchSources, History, Clock (Alarm/Timer), Onboarding (7 files), Memory (3 files)** — all in `com.contextsolutions.mobileagent.ui.*` commonMain, ViewModels bound in `uiModule` (`ui/di/UiModule.kt`), loaded by BOTH shells' `startKoin`.
- `:androidApp` still hosts: `ChatScreen` + `ChatViewModel` + the chat helpers (`AckPhrasePicker`, `MarkdownToPlainText`, `MemoryPromptCard`, `ThermalBanner`, `VoiceCommand`, `SpeechDictation`, `MarkdownMathText`, `LatexNormalizer`, `ImagePreprocessor`), `MainScreen` (the nav-host `when`), `DownloadScreen`/`DownloadViewModel` (deliberately Android-only), `MainViewModel`, `SystemMemoryStatusViewModel`, `ThemeModeViewModel`, `SpikeViewModel`, and all Android-only services.
- **Chat sub-increments done:** 8a (`64d7f05`) added commonMain `SessionState` (`inference/`) + `ChatSessionController` (`agent/`) with Android actual `AndroidChatSessionController`; `ChatViewModel` now takes `sessionController` (not `sessionManager`/`inventory`). 8b (`3bd0acd`) unified voice on commonMain `voice.ChatSpeaker`/`voice.TtsPreferences` (Android actuals `AndroidTtsSpeaker`/`SharedPreferencesTtsPreferences` live in `:shared` androidMain `voice/`; desktop `DesktopTtsPreferences`).
- **Seam pattern used throughout Phase 9:** commonMain interface in `:shared` (or `:ui` for `@Composable expect`), Android actual + desktop actual, Koin-bound in `androidModule` (`app/di/AndroidKoinModule.kt`) and `desktopModule` (`shared/src/desktopMain/.../di/DesktopModule.kt`). Pure formatting → `ui/util/Formatting.kt`. `koinInject<T>()` for screen-level seam access; `koinViewModel()` for VMs.

## Increment 8c — vision seam (do first; keep ChatScreen in :androidApp)
Refactor `ChatViewModel` off Android types so it's commonMain-ready, and switch `ChatScreen` to portable image picking. Per the plan doc §Increment 8 / 8c:
- `ChatViewModel`: `onImagePicked(uri: Uri)` → `onImagePicked(jpegBytes: ByteArray)`; `ChatUiState.pendingImageThumbnail: ImageBitmap?` → `pendingImageBytes: ByteArray?`; `UiMessage.User` drop `thumbnail: ImageBitmap?` (keep `imageBytes: ByteArray?`, used for both live + reloaded). Drop the `appContext: Context` ctor param (its only use was `ImagePreprocessor.prepare(appContext, uri)`). Remove `android.content.Context`/`android.net.Uri`/`androidx.compose.ui.graphics.ImageBitmap` imports.
- NEW `:ui` commonMain `ui/chat/ImagePicker.kt`: `interface ImagePicker { fun launch(onPicked: (ByteArray?) -> Unit) }` + `@Composable expect fun rememberImagePicker(): ImagePicker`. Android actual: `rememberLauncherForActivityResult(PickVisualMedia)` → read the `Uri` bytes via `contentResolver` → `koinInject<vision.ImagePreprocessor>().toModelJpeg(bytes)` (on a `rememberCoroutineScope`) → `onPicked(jpeg)`. Desktop actual: `koinInject<vision.FilePicker>().pickImage()` then `toModelJpeg`, on a coroutine.
- NEW `:ui` `ui/util/ImageDecode.kt`: `@Composable`-free `expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?` — Android `BitmapFactory.decodeByteArray(...).asImageBitmap()`; desktop `org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()`. (Used by `UserBubble` + the pending-image chip.)
- NEW Android actual `AndroidImagePreprocessor : vision.ImagePreprocessor` in `:shared` androidMain (`vision/`) — `toModelJpeg(bytes)` = decode + downscale longest edge ~768 + JPEG re-encode (adapt the existing `app.ui.chat.ImagePreprocessor.prepare` logic, which currently takes a `Uri`). Bind in `androidModule`. (Desktop `DesktopImagePreprocessor` + `FilePicker` are already bound.)
- `ChatScreen` (stays in `:androidApp` for 8c): replace the `PickVisualMedia` launcher with `rememberImagePicker()`; `ui.pendingImageThumbnail` → `ui.pendingImageBytes` (decode for the chip via `decodeImageBitmap`); `UserBubble(... message.imageBytes)` decoding via `decodeImageBitmap` (drop the `ImagePreprocessor.decodeThumbnail` call + the `thumbnail` param).
- Update `ChatViewModel*Test` if the vision signatures they touch changed (CancelTest/TtsTest/ReloadTest mostly don't touch vision — check).
- **Gates (all must pass):** `:ui:compileKotlinDesktop`, `:ui:compileAndroidMain`, `:shared:compileKotlinDesktop`, `DI_CHECK=1 ./gradlew :desktopApp:run`, `:androidApp:assembleDebug`, `:androidApp:testDebugUnitTest`, `:shared:desktopTest`. Commit.

## Increment 8d — the move (ChatScreen + ChatViewModel → :ui + nav host + desktop wiring)
Per the plan doc §Increment 8 / 8d:
- `ChatViewModel`: replace `android.util.Log` with an injected `logger: (String) -> Unit` (or reuse the `AgentLogger` pattern; preserve the `"ChatViewModel"` tag per invariant #28) and `System.currentTimeMillis()` with `kotlinx.datetime.Clock.System.now().toEpochMilliseconds()`. Then `git mv` `ChatViewModel` → `:ui` commonMain `ui/chat/`.
- Move the chat helpers to `:ui` commonMain `ui/chat/`: `AckPhrasePicker`, `MarkdownToPlainText`, `MemoryPromptCard`, `ThermalBanner`, `VoiceCommand` (all pure-Kotlin/Compose). Move `SpeechDictation` to `:shared` androidMain as the Android actual of commonMain `voice.Dictation` (desktop `VoskDictation` already bound).
- `ChatScreen` → `:ui` commonMain `ui/chat/`: swap `MarkdownMathText(text)` → `:ui` `MarkdownMath(text, renderMarkdown)` (the `renderMarkdown` flag is already on `UiMessage.Assistant`); **delete** `:androidApp` `MarkdownMathText.kt` + `LatexNormalizer.kt` (the `:ui` copies are canonical). Replace the RECORD_AUDIO runtime-permission flow + `SpeechRecognizer.isRecognitionAvailable` with a small **permission seam** (`@Composable expect fun rememberMicPermission(): MicPermission` with `granted`/`request()`, or similar) + drive `Dictation` through the commonMain seam; Android actual wraps `rememberLauncherForActivityResult(RequestPermission)`, desktop reports granted=true (Vosk needs no OS permission prompt). Keep the echo-suppression grace-tail + voice-command behavior (#42).
- Move the nav-host `when` from `:androidApp` `MainScreen.kt` into `:ui` commonMain (it now can reference all screens since they're all in `:ui`); keep `MainViewModel`-driven gating (onboarding/model-present). `MainViewModel` reads `ModelInventory` (Android) + onboarding/model state — decide: either move `MainViewModel` to `:ui` behind a small seam, or keep a thin Android `MainScreen` wrapper that supplies the gating flags to a shared `AppNavHost(...)`. Simplest: a shared `AppNavHost` composable in `:ui` taking the gating state + the warm-up hook as params; each shell supplies them.
- DI: bind `ChatViewModel` in `uiModule` (`viewModel { }` or `viewModelOf` — it has all-Koin deps now). Desktop: add a `:desktopApp` Koin module (or extend startKoin) binding `ChatSessionController` → a new `DesktopChatSessionController(warmModel)` (WarmModel is in `:desktopApp`; make WarmModel Koin-bound or pass it). Desktop `DesktopChatSessionController.state` can be a `MutableStateFlow<SessionState>` (Unloaded→Loading→Loaded(Accelerator.CPU) around first `newSession()`); `newSession()` = `warmModel.session()`.
- Wire the real `ChatScreen`/`AppNavHost` into the `:desktopApp` window (replace/augment `QueueStatusScreen`).
- Update/relocate Chat tests; keep `:androidApp:testDebugUnitTest` green.
- **Gates:** same 7 as above. Commit. This is the largest, highest-risk increment (live Android primary surface) — keep `:androidApp:assembleDebug` green at the commit.

## Increment 9 — shell shrink + release prep
- Shrink `:androidApp` to the thin shell: `MainActivity`, `MobileAgentApplication` (Koin start), `AndroidKoinModule`, Android-only services (clock receivers, foreground service, download/WorkManager), and Android actuals. Remove now-dead `app.ui.*` files left behind by the moves.
- `:desktopApp`: host the real screens (the shared `AppNavHost`/`ChatScreen`), replacing `PlaceholderScreen`/`QueueStatusScreen` as appropriate (keep the tray + warm-model runtime).
- Bump `appVersionName` in `androidApp/build.gradle.kts` (~line 102) from `0.0.3-beta` → `1.0.0`.
- Prepare the **`v1.0.0`** tag for the eventual merge of `feature/desktop-cmp` → `main`. **Do NOT tag or merge without the user's explicit go-ahead.**

## Environment / boundaries (unchanged from Phases 4–8)
- Headless Linux x86-64: no display, no Android device/emulator, no macOS/Windows. On-screen rendering, installed-app launch, and on-device Android runtime are the USER's manual checks. Keep anything that decodes images / loads Skia lazy (inside `application{}`/Activity, not class-init) so headless `DI_CHECK` doesn't crash (Phase-8 lesson).
- Green gates per increment: `:ui:compileKotlinDesktop`, `:ui:compileAndroidMain`, `:shared:compileKotlinDesktop`, `:desktopHarness:compileKotlin` (if touched), `DI_CHECK=1 ./gradlew :desktopApp:run`, `:shared:desktopTest`, moved-screen unit tests. **Cardinal rule: `:androidApp:assembleDebug` MUST stay green at every commit.** Run `:androidApp:testDebugUnitTest` after VM moves.
- Do NOT run `:ui:assemble`, iOS targets, or `compileCommonMainKotlinMetadata` (pre-existing broken — Phase 1 findings). Work around any common-clean-ness pressure in the desktop/android source sets, not the iOS/metadata path.
- All gradle from `/home/lawrenceley/src/mobile-agent/android-app`.

## Working norms
- Commit each verified-green increment to `feature/desktop-cmp`. End commit messages with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- Update `docs/DESKTOP_PORT_PLAN.md` (Phase 9 status log) + the `project_desktop_port` memory as each increment lands.
- Don't push unless asked. Don't tag `v1.0.0` or merge to `main` without explicit go-ahead.
- Lesson from inc 5: when moving a VM/screen out of `:androidApp`, grep **all** `:androidApp` referrers (e.g. `ChatScreen` imports several migrated VMs), not just `MainScreen`/DI/tests.

Begin by reading the three docs above + verifying branch state, then start increment 8c.
