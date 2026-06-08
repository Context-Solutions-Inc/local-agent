# Remote Ollama server for the chat LLM (PR #56)

Lets the user point the **large chat LLM** at an [Ollama](https://ollama.com) server on
their LAN, so the heavy **text + image** inference runs remotely (e.g. on a faster
machine) while everything else — the pre-flight classifier, embedder, search verticals,
and memory — stays fully **on-device**. Works on **both** desktop and Android.

When a host/port is configured the local LLM is not used; if the server is unreachable
the app silently falls back to the on-device model.

## Why

The on-device LLM is the slow part (Gemma 4 E2B: ~8 tok/s on a Pixel 7, CPU-bound on
many desktops). Users with a capable machine on their network can get much faster
responses by running the model in Ollama there. The classifier/embedder are small and
privacy-sensitive, so they deliberately stay local — only the big model is offloaded.

## Architecture

`InferenceEngine` is the single shared seam. A new `RoutingInferenceEngine` (commonMain)
wraps the platform's local engine and routes to a new `OllamaInferenceEngine` (commonMain,
pure Ktor) when configured + reachable. Because the router sits **below** the Android
`InferenceSessionManager` and the desktop `WarmModel` runtime, both platforms get remote
routing with no change to the agent loop or the lifecycle/session layers.

```
:shared commonMain  AgentLoop ── InferenceEngine (unchanged seam) ──────────────────┐
                     RoutingInferenceEngine (NEW) ─ picks backend at loadModel        │
                       ├─ local  → LiteRtInferenceEngine (Android) /                  │
                       │            LlamaServerInferenceEngine (desktop)              │
                       └─ remote → OllamaInferenceEngine (NEW, pure Ktor)             │
                                    loadModel → health-probe (else fall back to local)│
                                    generate  → POST /v1/chat/completions (SSE)        │
                     OllamaClient (NEW)        → GET /api/tags (model discovery)       │
                     OllamaConnectionMonitor (NEW) → offline/online auto-recovery      │
:shared *Main        OllamaPreferences (host/port/chatModel/visionModel, reactive)    │
:ui                  Settings "Ollama server" section (host/port, test, dropdowns)    │
```

Endpoint choice: Ollama's **OpenAI-compatible** `/v1/chat/completions` (SSE), which
mirrors `LlamaServerInferenceEngine` almost line-for-line — same base64 `image_url`
data-URI (image FIRST, then text; invariant #39 preserved), same SSE delta parsing,
same sampling/stop mapping. `/api/tags` (native) is used only for model discovery.

## Components

- **`OllamaPreferences`** (commonMain interface; `SharedPreferences` on Android,
  `DesktopJsonStore` on desktop) — reactive `OllamaConfig(host, port, chatModel,
  visionModel)`. `isConfigured = host & port & chatModel set`. The whole config is one
  JSON blob under a single key.
- **`OllamaClient`** — `/api/tags` model listing (vision-capability is a best-effort
  name/family heuristic that only sorts the Settings dropdown) + a fast `health()` probe
  with a tight per-request timeout.
- **`OllamaInferenceEngine`** — `loadModel` health-probes and builds an un-timed
  streaming client (the factory's 10 s request timeout would abort a long stream);
  `generate` streams `/v1/chat/completions`. The trailing USER turn carries any image as
  a multipart `content` array; the **vision model** serves image turns when set, else the
  **chat model**. `keep_alive` is sent so Ollama keeps the model resident between turns.
- **`RoutingInferenceEngine`** — decides the backend once per `loadModel`: configured +
  reachable → Ollama; configured + unreachable → **local fallback**; unconfigured →
  local. The `RoutedHandle` records which backend loaded, so `generate`/`unload` dispatch
  correctly (and `activeAccelerator` delegates through — see below). Becomes
  `single<InferenceEngine>` on both platforms.
- **`OllamaConnectionMonitor`** — auto-recovery (see next section).
- **Settings UI** — an "Ollama server" section at the bottom of the screen: host/port
  fields, **Test connection** (populates the model dropdowns via `/api/tags`), **chat**
  and **vision** model pickers (vision-capable models sorted first), Save/Clear, live
  status. Persisted via `OllamaPreferences`.

## Lifecycle: reload-on-change and offline/online recovery

The backend is cached in the resident handle, so two things must drop it and force the
next turn to re-decide:

1. **Settings change** — the app (`MobileAgentApplication`) / desktop (`Main`) observe
   `OllamaPreferences.configFlow()` and call `InferenceSessionManager.forceUnload(Manual)`
   / `WarmModel.invalidate()`.
2. **Server offline → online** — `OllamaConnectionMonitor`. `OllamaInferenceEngine`
   notifies it on a **connection** failure (vs an HTTP-status error, which means the
   server is up) and on a successful connect. The monitor emits `reloadRequests` that the
   same session owners observe:
   - **Server lost** → immediate reload → next turn falls back to local; a background
     watch polls the server (~15 s interval).
   - **Server back** → the watch requests a reload → next turn reconnects to Ollama.
   - **Healthy connect** → cancels the watch.

   Net: fall-back to local happens on the turn after the first failure; reconnect happens
   automatically within ~15 s of the server returning, no user action or restart needed.

## UI: accelerator banner

`Accelerator.REMOTE` is reported by the Ollama handle (and delegated up through
`RoutedHandle`). The chat `SessionBanner` shows nothing for `Loaded(REMOTE)` — the
on-device "Loaded on CPU/GPU…" banner doesn't apply to remote generation. The remote
server status is shown in Settings instead. `LiteRtInferenceEngine.backendFor` rejects
`REMOTE` (it never reaches local backend selection).

## Android cleartext

Local Ollama serves plain `http://` on a LAN IP. `network_security_config.xml`
(`base-config cleartextTrafficPermitted="true"`) + the manifest flags permit it. The IP
is entered at runtime so a per-domain entry isn't possible; the app's other traffic is
HTTPS and unaffected. Desktop (CIO) needs nothing.

## Decisions / limitations

- **Image turn with no vision model selected** routes to the **chat model** (most Ollama
  chat models, e.g. `gemma3:4b`, are multimodal) rather than cross-switching to the local
  engine mid-session — the routed backend is fixed per resident handle. If the chat model
  can't do vision, Ollama returns an error for that turn.
- **No think-channel stripping** on the Ollama path (unlike `LlamaServerInferenceEngine`'s
  `ThinkingStripper`, which is desktop-only) — pick a non-reasoning chat model, or follow
  up to port the stripper to commonMain.
- **Tool calling** is not used on this path (the agent uses none today).

## Files

- Engines/seam: `shared/commonMain/.../inference/{OllamaInferenceEngine, OllamaClient,
  RoutingInferenceEngine, OllamaConnectionMonitor}.kt`; `Accelerator.REMOTE` in
  `InferenceEngine.kt`; `LiteRtInferenceEngine.backendFor`.
- Prefs: `shared/commonMain/.../preferences/OllamaPreferences.kt` +
  `androidMain/SharedPreferencesOllamaPreferences.kt` +
  `desktopMain/DesktopOllamaPreferences.kt`.
- DI: `DesktopModule.kt`, `AndroidKoinModule.kt`.
- App observers: `MobileAgentApplication.kt` (Android), `desktopApp/.../Main.kt` +
  `WarmModel.invalidate()`.
- UI: `ui/.../settings/{SettingsScreen, SettingsViewModel}.kt`; banner in
  `ui/.../chat/ChatScreen.kt`.
- Networking: `androidApp/.../res/xml/network_security_config.xml` + `AndroidManifest.xml`.
- Tests (`shared/desktopTest`): `OllamaRequestTest`, `RoutingInferenceEngineTest`,
  `OllamaConfigTest`, `OllamaConnectionMonitorTest`.

## Manual validation (before merge)

1. Stand up Ollama on a LAN box: `ollama serve`; `ollama pull gemma3:4b` + a vision model
   (e.g. `qwen2.5vl:7b`); confirm `curl http://<ip>:11434/api/tags`.
2. **Desktop:** Settings → Ollama server → host/port → Test connection → pick chat +
   vision models → Save. Send a text chat (streams from Ollama) and an image chat (vision
   model answers). Clear → reverts to local llama-server.
3. **Android (Pixel 7):** same flow; confirm cleartext LAN works and image turns hit the
   vision model. Kill Ollama mid-session → next turn falls back to local (no crash);
   bring it back → reconnects within ~15 s.
4. **Speed:** 2nd+ turns are fast (model stays warm via `keep_alive`).
5. **Regression:** with nothing configured, both platforms behave exactly as before.

## PR #73 — generalization to any OpenAI-compatible backend + SSL + on/off switch

PR #73 ("Remote LLM Connection") generalized the seam to **two backend types** via
`OllamaConfig.serverType` (`RemoteServerType.OLLAMA` / `OPENAI`), kept behind the **same**
`InferenceEngine` / `OllamaInferenceEngine` / `OllamaClient` / `OllamaPreferences` names —
they ARE the seam; extend, never rename. The Settings section was renamed **"Remote LLM
Connection"**. The OpenAI type targets OpenAI / OpenRouter / LM Studio / vLLM /
llama-server / LocalAI.

- **(a) URL shape differs by type.**
  - *Ollama:* `baseUrl()` = `scheme://host:port`; append `RemoteServerType.modelsPath`
    (`/api/tags`) + `chatCompletionsPath` (`/v1/chat/completions`).
  - *OpenAI:* the user supplies the **full base URL incl. any path** (OpenAI `base_url`
    convention, e.g. `https://openrouter.ai/api/v1`), used verbatim; append `/models` +
    `/chat/completions`. The **port field is ignored** and `isConfigured` doesn't require
    it. `OllamaClient.parseOpenAiModels` reads `/v1/models`' `{"data":[{"id":…}]}`.
- **(b) SSL.** `OllamaConfig.useSsl` flips the bare-host scheme to `https://`; an OpenAI
  server forces it (`sslEnabled = useSsl || serverType == OPENAI`); an explicit
  `http(s)://` the user typed always wins. The Settings SSL checkbox is locked-checked for
  OpenAI. HTTPS uses the system trust store — **self-signed / custom-CA certs are out of
  scope** (handshake fails).
- **(c) `keep_alive` is Ollama-only** — `buildOllamaChatRequest` omits it for `OPENAI`
  (strict servers reject unknown fields).
- **(d) API key required for OpenAI** (Settings gates Save on `hasOllamaApiKey`); optional
  for Ollama.
- **(e) On/off switch.** `OllamaConfig.enabled` (default `true` so upgrades keep routing).
  Routing gates on **`isActive = enabled && isConfigured`**, NOT `isConfigured`. Toggling
  writes `enabled` to the persisted config (preserving server details) → `configFlow` →
  re-decide.
- **(f) Diagnostics.** `OllamaClient` / `OllamaInferenceEngine` log the full outbound URL +
  Bearer token (cleartext) + body — a deliberate on-device debug aid that bypasses the
  redacting ktor logger. A 200 with no SSE `data:` lines (e.g. a wrong base path returning
  HTML) surfaces as an error, not an empty bubble.

This is **CLAUDE.md hard invariant #44** — the full rule. The seam invariant itself:
the remote path lives entirely behind `InferenceEngine`; `RoutingInferenceEngine` decides
the backend once per `loadModel` and never gets special-cased above the seam.
