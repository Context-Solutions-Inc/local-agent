# Voice I/O — dictation (STT) + read-aloud (TTS)

As-built record for the in-chat microphone (speech-to-text) and speaker (text-to-speech)
features. These are **CLAUDE.md hard invariants #42 (Android) and #48 (desktop)** — the
full rules live here; CLAUDE.md keeps one-line pointers.

Shipped across PRs: #51 (Android mic + speaker), #64 (desktop Vosk STT), #66 (desktop TTS
stop fix + voice picker + Piper neural engine), #67 (streaming dictation partials).

## Seams

Voice I/O on **Android is entirely `:androidApp`** (no `:shared` seam), behind two
interfaces: `ChatSpeaker` (TTS) and `SpeechDictation` (STT). Input row order in the chat
composer: **mic · speaker · photo · Send**.

Desktop uses the same conceptual seams with desktop-specific implementations
(`DesktopTtsSpeaker`, Vosk-based dictation). Some desktop knobs (voice/engine/rate, UI
zoom) live on **concrete** desktop preference classes that are deliberately kept OFF the
shared interfaces — see [Desktop voice configuration](#desktop-voice-configuration).

---

## #42 — Android voice I/O

### Read-aloud (TTS) fires only at `Done`

Read-aloud fires **ONLY at `AgentEvent.Done`, never on streaming tokens.** `ChatViewModel`
speaks `MarkdownToPlainText.strip(message.text)` (citations excluded); `AndroidTtsSpeaker.speak`
uses `QUEUE_FLUSH`.

### Working-on-it ack + heartbeat gate on `GenerationStarted`

The "working on it" ack + the 5 s "still working" heartbeat gate on
**`AgentEvent.GenerationStarted`, NOT `send()`** — `AgentLoop` emits it right before
`session.generate`, so deterministic short-circuits (which return earlier) stay silent.
**Don't move the ack into `send()`.** The heartbeat runs only GenerationStarted→Done.
`AgentEvent` is a sealed interface — a new variant breaks the exhaustive `when` in
`ChatViewModel.onAgentEvent`.

### Mic dictation is a continuous, session-only toggle

`SpeechRecognizer` is single-shot, so `SpeechDictation` self-restarts on every
result/timeout (restart on all errors except `ERROR_INSUFFICIENT_PERMISSIONS`, 150 ms
debounce). Needs `RECORD_AUDIO` + a `<queries>` `RecognitionService` manifest entry
(Android 11+). **Defaults OFF each launch, NOT persisted.** (The speaker toggle IS
persisted via `TtsPreferences`.) There is no "microphone on" voice command.

### Streaming dictation partials (PR #67)

Dictation streams the **live transcript into the prompt box** — partials are display-only;
commands + committed text key off finalized results.

- `Dictation` exposes `partials: Flow<String>` (live, rewrites mid-utterance) alongside
  `results: Flow<String>` (finalized utterances).
- Android sets `EXTRA_PARTIAL_RESULTS=true` and emits in `onPartialResults`; desktop Vosk
  emits `recognizer.partialResult`'s `"partial"` field per audio frame (deduped).
- `ChatScreen` keeps a `committedInput` anchor and renders `committedInput (+ space) + partial`.
  A finalized `results` emission (NONE branch) **or a manual keystroke** promotes the
  partial into `committedInput`.
- **`VoiceCommand.match` runs ONLY on `results`** (whole utterances), never on a partial —
  the transient command partial flashing in the box is discarded on finalize.
- Echo suppression drops partials during TTS playback too.
- **Don't act on a partial** and **don't send `input`** for a voice SEND — send
  `committedInput`.

### Echo suppression uses a grace tail

Echo suppression uses a grace tail, **not** instantaneous `isSpeaking`. During read-aloud
the mic stays listening command-only (so "speaker off" can interrupt). Non-command text is
dropped while `suppressDictationText` = `ttsSpeaking` + a 2.5 s trailing grace. Best-effort;
the button is the guaranteed stop.

### Voice commands match the whole utterance

`VoiceCommand.match` (case/punctuation/whitespace-insensitive) over the **whole utterance**:
send / cancel / clear / new chat / microphone off / speaker off / speaker on. SEND reuses
the Send button's guard; speaker on/off route through the idempotent `setTtsEnabled`.

### Android TTS uses the OS voice settings

TTS voice/rate/pitch is the **OS setting on Android — no in-app picker.** `AndroidTtsSpeaker`
only sets `language = Locale.getDefault()`. (Desktop is different — see below.)

---

## #48 — Desktop read-aloud (PR #66)

### Stopping is OS-specific (the Linux `spd-say` daemon trap)

Desktop read-aloud shells out per-OS: `say` (macOS) / `spd-say --wait` (Linux) / PowerShell
`SpeechSynthesizer` (Windows). **Stopping it is OS-specific.** `DesktopTtsSpeaker.stop()`
calls `process.destroy()`, which silences macOS `say` and the Windows synthesizer
(in-process) but **NOT Linux**: `spd-say` is only a *client* handing text to the
speech-dispatcher daemon, which keeps playing after the client dies. So `stop()` **also
issues `spd-say --cancel`** on Linux — that's what makes the speaker-mute toggle silence
in-flight speech there.

### Desktop voice configuration

**Desktop voice IS configurable** (unlike Android): engine (Linux output module,
`spd-say -o`) / voice (`-y` / `say -v` / `SelectVoice`) / rate live on the **concrete
`DesktopTtsPreferences`** (`tts_prefs.json`), deliberately OFF the shared `TtsPreferences`
interface (same split as desktop UI-zoom, invariant #45). `DesktopTtsVoices` enumerates
options (`spd-say -O`/`-L`, `say -v ?`, PowerShell `GetInstalledVoices`); the Settings
picker is a desktop-only `expect/actual DesktopVoiceSection()` (the Android actual is a
no-op).

### Bundled Piper neural engine (the real quality win)

The real quality win is the **bundled Piper neural engine, not the OS engine.**
`engine == DesktopVoiceConfig.PIPER_ENGINE` ("piper") routes `DesktopTtsSpeaker` to
`PiperSpeechSynthesizer`. Piper is self-contained + offline:

- `PiperBinaryStore` downloads the prebuilt `rhasspy/piper` executable (pinned
  `PiperRelease.TAG`, per-OS/arch asset table with verified sha256, extracted via system
  `tar` / Windows `ZipInputStream`; the `$ORIGIN`-rpath bundle ships onnxruntime +
  espeak-ng-data).
- `PiperVoiceStore` downloads the ONNX voice + `.onnx.json` (HF `rhasspy/piper-voices`,
  `en_US-lessac-medium` ≈ 63 MB).
- Both use the same `DesktopModelDownloader` / `DesktopModelInventory` / app-data layout as
  the LLM and Vosk models.
- Synth is `piper --model … --config … --output-raw` (raw S16LE mono @ the model's sample
  rate) streamed to a **`javax.sound.sampled.SourceDataLine`** — no `aplay`/Python.
- So Piper **stops instantly** (in-JVM line close + process kill; the daemon-cancel problem
  is Linux-`spd-say`-only).
- Rate maps to `--length_scale` (smaller = faster).
- The picker offers Piper only when `PiperRelease.assetForHost() != null`; selecting it
  triggers `prepare()` (pre-download with progress in `PiperState`).

OS speech-dispatcher modules remain the non-neural fallback.

### Desktop STT survives suspend/resume (debug PR, draft)

On Linux a laptop suspend reclaims the microphone device; on resume the JVM's
`javax.sound.sampled` hands back a `TargetDataLine` that opens "successfully" but returns
`read() == 0` **forever** — the cached device handle is stale and reopening it does **not**
recover (confirmed on-device: the reopened line + a force-close watchdog still only saw
empty reads). The original capture loop did `if (read <= 0) continue` forever, so after a
resume Vosk got no audio yet `isListening` stayed `true` — the mic button looked active but
nothing transcribed, with no log.

Two parts to the fix:

- **Capture source.** On Linux, capture now runs through a spawned recorder CLI
  (`parec` → `arecord`, auto-detected via `which`) piping raw S16LE/16 kHz/mono into Vosk —
  the same `ProcessBuilder` + raw-PCM pattern as `PiperSpeechSynthesizer`. A **fresh process
  per session** binds to the *current* default source, so it survives suspend/resume.
  `parec` covers PulseAudio and PipeWire (via `pipewire-pulse`); `arecord` is the ALSA
  fallback. macOS/Windows (and Linux with no recorder CLI) keep the `TargetDataLine` path.
  Recorder pipes can split mid-sample, so the loop carries a trailing odd byte to keep
  16-bit frames aligned.
- **Recovery loop + watchdog.** Capture is split into reopenable per-mic sessions. A pure
  `classifyRead(read, consecutiveZero)` maps a read result to `Data` / `KeepWaiting` /
  `Stale` (recorder EOF / `-1` / a long run of empty line reads ⇒ `Stale`; a throwing read
  too). On `Stale` the outer loop **reopens** (keeping `Model`/`Recognizer` alive,
  `recognizer.reset()` between sessions) with bounded backoff (300 ms → 3 s); a session that
  reads **no** audio counts toward a give-up guard (`MAX_BARREN_SESSIONS`) so a gone device
  can't respawn forever. `isListening` drops during the gap and flips back true only on
  reopen. A **stall watchdog** force-`close()`s a source that has delivered no audio for ~3 s
  — the catch-all for a read wedged with no return value (closing it from another thread
  unblocks the read → classified stale → recovery).

`VoskDictation`'s `logger` writes to `System.err` un-gated, so recovery/stall lines are
visible even in a packaged build (per-frame chatter stays behind `DesktopDiag.verbose`).
Watch for `[Dictation]` lines: `capturing via recorder: parec`, `no audio for …ms — source
appears stale, forcing reopen`, `microphone source went stale — reopening in …ms`,
`microphone open — dictation listening`.

`classifyRead`/`backoffMs` are unit-tested hardware-free in `VoskReadClassifierTest`.

---

## Key files

- **Android STT/TTS:** `androidApp/.../voice/{AndroidTtsSpeaker, SpeechDictation}.kt`;
  `ChatSpeaker` / `Dictation` seams; `VoiceCommand`, `MarkdownToPlainText`.
- **Desktop STT (Vosk):** `VoskModelStore` — auto-downloads `vosk.tar.gz` from the public models
  CDN (`downloads.contextsolutions.com/models`, no auth) and extracts via system `tar` (PR #22; was a `.zip` from `alphacephei.com`
  unpacked with `java.util.zip`). Desktop `Dictation` actual.
- **Desktop TTS:** `desktopApp/.../voice/{DesktopTtsSpeaker, DesktopTtsPreferences,
  DesktopTtsVoices, PiperSpeechSynthesizer, PiperBinaryStore, PiperVoiceStore,
  PiperRelease}.kt`; `DesktopVoiceConfig`, `PiperState`.
- **UI:** `expect/actual DesktopVoiceSection()` in `:ui` settings; chat composer mic/speaker
  buttons + `committedInput` anchor in `ChatScreen.kt`.
- **Manifest (Android):** `RECORD_AUDIO`, `<queries>` `RecognitionService`.
