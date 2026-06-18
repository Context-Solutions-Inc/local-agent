# Local Agent — User Guide

How to use the assistant's features once it's built and installed (see
[BUILD.md](BUILD.md) to get there). Covers configuring search sources, forcing a web search, photo
input, voice in/read-aloud, and desktop appearance.

## Contents

- [Configuring search sources](#configuring-search-sources)
- [Forcing a web search](#forcing-a-web-search)
- [Asking about a photo](#asking-about-a-photo)
- [Voice input and read-aloud](#voice-input-and-read-aloud)
- [Desktop appearance & window](#desktop-appearance--window)

---

## Configuring search sources

**Settings → Search sources** lets you control which sites each search vertical (General, News,
Weather, Sports, Finance) draws from. Each row shows a source's display name, domain, and kind; tap
the **pencil** to edit it in place or the **✗** to remove it. **Add source** opens the same dialog
for a new entry.

A source has four fields:

| Field | Notes |
|---|---|
| **Domain** | Bare host, e.g. `cbc.ca`. Used as the citation-chip label and (for `BRAVE_SITE_FILTER`) the `site:` filter. |
| **Display name** | Optional human label; defaults to the domain. |
| **Kind** | How the result is fetched + parsed (see below). |
| **Endpoint URL or template** | Hidden for `BRAVE_SITE_FILTER`. Supports the placeholders `{query}`, `{country}`, `{region}`, `{city}` (and `{lat}`/`{lon}` for coordinate-driven weather feeds), substituted at query time. |

### Source kinds

- **`BRAVE_SITE_FILTER`** — reuses the Brave Search backend with a `site:<domain>` filter (no
  endpoint needed). The default for General/News/Sports/Finance.
- **`RSS`** — fetches an RSS 2.0 / Atom feed URL and parses `title`/`link`/`description`/`pubDate`.
- **`DWML`** — fetches NWS Digital Weather Markup Language XML (US weather; coordinate-driven).
- **`HTML`** — fetches a web page and extracts readable `<article>`/`<p>` text.
- **`JSON`** — fetches a JSON endpoint; the raw body (truncated) is handed to the model.

> **Routing note:** the **News** vertical runs a composite adapter that routes each source to the
> parser for its kind — `BRAVE_SITE_FILTER` sources go through Brave, and `RSS`/`DWML`/`HTML`/`JSON`
> sources are fetched + parsed directly — so News can exercise **all five kinds at once**. Weather
> uses the direct-fetch feed path; Sports/Finance/General run the Brave `site:` path. See CLAUDE.md
> invariants #31–#37 for the full per-vertical wiring (including why Finance uses Brave's
> `/web/search` while the other web verticals use `/llm/context`).

### Example: testing each kind under News

Add these under **Settings → Search sources → News → Add source** to exercise every parser (then ask
a news query and watch logcat `-s VerticalSearch:I BraveApi:I` for the
`[vertical:NEWS] GET <domain> kind=<KIND>` or `brave query=` line):

| Kind | Domain | Endpoint URL or template |
|---|---|---|
| JSON | `newsapi.org` | `https://newsapi.org/v2/everything?q={query}&language=en&sortBy=publishedAt&apiKey=YOUR_NEWSAPI_KEY` |
| RSS | `npr.org` | `https://feeds.npr.org/1001/rss.xml` |
| DWML | `forecast.weather.gov` | `https://forecast.weather.gov/MapClick.php?lat=40.71&lon=-74.01&unit=0&lg=english&FcstType=dwml` |
| HTML | `apnews.com` | `https://apnews.com/hub/world-news` |
| BRAVE_SITE_FILTER | `bbc.com` | *(endpoint hidden — domain used as the `site:` filter)* |

Notes: `{query}` is substituted into the JSON URL — the NewsAPI key must be hardcoded in the URL (the
fetcher sends no custom headers). The DWML example hardcodes coordinates so it doesn't need a saved
location. To isolate one parser per turn, configure only that one kind under News (the composite runs
only the side(s) with matching sources).

## Forcing a web search

Normally the app decides whether a question needs the web automatically (an on-device pre-flight
classifier). When you *know* you want a live web lookup — and don't want to rely on that classifier —
start your message with an explicit search command:

- **`web search …`** — e.g. *"web search the URL of the Android Open Source Project"*
- **`search the web for …`** — e.g. *"search the web for the best pizza in Toronto"*
- **`search online …`** — e.g. *"search online who won the masters"*

This always fires a Brave search for that turn. The command words are stripped before the query is
sent, so *"web search the URL of AOSP"* searches for **the URL of AOSP**, not the literal command.
Topic routing still applies — an explicit search about sports or finance still uses the matching
vertical.

Notes:

- **The command must be at the start of the message.** This is deliberate, to avoid false triggers:
  *"how do web search engines work"* is a normal question and is **not** forced to the web.
- **Only `web search` / `search the web` / `search online` trigger it** — not *"google …"* or
  *"look up …"* (those mis-fire too easily, e.g. *"google announced layoffs"*).
- **Search must be enabled.** If web search is turned off in Settings (or no Brave key is
  configured), an explicit command still won't fire — the app answers from the model instead.

> Diagnostics: `adb logcat -s ClassifierModule:I` shows `decision=FireSearch … forced=explicit` with
> the stripped `rewritten="…"` query on a forced turn.

## Asking about a photo

The assistant can look at a photo and answer questions about it, running Gemma's vision tower
entirely on-device.

1. In the chat, tap the **image icon** to the left of **Send**.
2. Pick a photo from the Android Photo Picker (no storage permission needed).
3. The selected image appears as a thumbnail chip above the input — tap the **✗** to remove it before
   sending.
4. Optionally type a question ("what breed is this dog?", "read the sign in this photo"). You can also
   send an image with no text.
5. Tap **Send**. The photo renders in your chat bubble and the model answers.

Notes:

- **One image per turn.** The photo is downscaled to ~768 px and JPEG-encoded before it reaches the
  model (Gemma's vision input size).
- **Image turns skip web search.** A photo question is answered from the image itself — the pre-flight
  classifier and search verticals are bypassed for that turn.
- **Photos persist in the thread.** The image is saved with the conversation, so it reappears in your
  chat bubble when you resume that conversation from history.
- **The model only sees the current photo.** Earlier images are kept for display only — they are
  **not** re-sent to the model on later turns (that would waste context and memory). Each turn, the
  model sees at most the photo you just attached.
- **All on-device.** The image never leaves the phone — it is not uploaded anywhere (Brave Search is
  not involved in an image turn).

> Diagnostics: `adb logcat -s AgentLoop:I LiteRtInferenceEngine:I` shows
> `[turn] image attached — skipping preflight/search` and `current user turn carries image: <N> bytes`
> on an image turn. The native `litert: No dispatch library found / Failed to initialize Dispatch API`
> lines are harmless — LiteRT-LM probing for an optional NPU vendor delegate that the Pixel 7 doesn't
> have; it falls back to the GPU vision path.

## Voice input and read-aloud

The chat input row has four controls — **🎤 microphone · 🔊 speaker · 🖼 photo · Send** — that add
hands-free voice in and voice out. On Android both use the OS's built-in, offline speech services (no
cloud, no LLM calls, consistent with the app's privacy posture); on desktop, dictation uses the
bundled offline Vosk engine and read-aloud uses the per-OS TTS (with an optional bundled Piper neural
voice).

### Read-aloud (text-to-speech)

Tap the **speaker icon** to toggle read-aloud on/off. The setting **persists across launches** (the
icon is tinted when on).

- When on, every finished assistant answer is **read aloud** — conversational replies, weather/finance
  cards, and search summaries alike. Citations are skipped and markdown/LaTeX formatting is stripped so
  only clean prose is spoken.
- Answers are read only once **fully generated** (streaming tokens aren't spoken — that jitters), so to
  fill the gap the assistant speaks a short **"working on it"** cue when a turn starts and a **"still
  working"** heartbeat every ~5 s during a long generation. The fast deterministic cards
  (weather/finance/clock/my-list) skip these cues.

**Choosing a voice (Android) — it's an OS setting.** The app speaks with whatever text-to-speech
engine and voice you've selected system-wide. To change it (or audition others):

> **Settings → System → Languages (& input) → Text-to-speech output** — or search Settings for
> *"text-to-speech"*.

There you pick the **preferred engine** (e.g. *Speech Recognition & Synthesis from Google*),
**language**, **speech rate**, and **pitch**, with a **▶ Play** to preview. The app inherits your
choice automatically — no in-app voice picker needed. **On desktop**, the engine/voice/rate are
configurable in-app (Settings → Appearance/Voice), including the bundled offline Piper neural engine.

### Dictation (speech-to-text)

Tap the **microphone icon** to toggle dictation on. The first time, the app asks for **microphone
permission** (continuous in-app recognition needs it). The mic **stays on until you toggle it off** —
it's session-only and defaults off each launch, so the app never opens the mic at startup. (There's no
"microphone on" voice command for the same reason — nothing would be listening.)

- Spoken words are transcribed into the input box; review/edit, then **Send** (or just say *"send"*).
- The microphone is greyed out if your device has no speech-recognition service (Android) or no Vosk
  model installed (desktop).

**Voice commands.** While dictating, these spoken phrases fire an action instead of being typed.
Matching is whole-utterance, so a command word used inside a sentence (e.g. *"send me the report"*)
stays as text and doesn't trigger.

| Say | Action |
|---|---|
| "send" · "send it" · "send message" · "submit" | Send the current input |
| "cancel" · "cancel that" | Cancel an in-progress response |
| "clear" · "clear text" · "clear input" | Empty the input box |
| "new chat" · "new conversation" · "start new chat" | Start a fresh conversation |
| "microphone off" · "mic off" · "turn off the microphone" | Turn dictation off |
| "speaker off" · "turn off the speaker" | Turn read-aloud off |
| "speaker on" · "turn on the speaker" | Turn read-aloud on |

### Using both at once

You can dictate *and* have answers read aloud. While the speaker is talking, the mic stays in
**command-only mode**: spoken commands (e.g. *"speaker off"*) still work, but regular dictation is
ignored so the assistant's own voice doesn't get transcribed back into the box.

> **Caveat:** interrupting playback by voice is acoustically hard — while the assistant is talking at
> volume, the mic mostly hears *it*, not you, so *"speaker off"* spoken over the playback may not
> register reliably (a pause between the assistant's words helps). The on-screen **speaker button** is
> the guaranteed instant stop.

## Desktop appearance & window

- **UI zoom:** `Ctrl`/`Cmd` `+` / `-` scales the whole desktop UI (icons, forms, spacing **and** text
  — distinct from the text-only font-size slider in Settings); `Ctrl`/`Cmd` `0` resets. Persisted
  across launches.
- **Theme:** a monochrome scheme — white background + black trim (light), near-black `#121212` + white
  trim (dark). Light / Auto / Dark live in Settings → Appearance. **Auto** follows the OS theme on
  macOS and Windows; on **Linux** the underlying `isSystemInDarkTheme()` is unreliable
  ([JetBrains CMP-6028](https://youtrack.jetbrains.com/projects/CMP/issues/CMP-6028/Implement-isSystemInDarkTheme-for-desktop)),
  so pick Light/Dark explicitly there.
- **Window size/position** (and maximized state) is remembered and restored on the next launch.
- **Chat bubbles** widen to fill wide windows (capped for readable line length); narrow windows /
  mobile keep the compact width.
- **Closing the window hides to the system tray** (the app and model stay resident); quit from the
  tray menu. Run with `LOCALAGENT_HEADLESS=1` to start as a background service with no window (see
  [BUILD.md](BUILD.md)).
