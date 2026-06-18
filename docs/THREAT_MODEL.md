# Threat Model & Security Analysis

A code-grounded analysis of Local Agent's security posture: what it protects, where the trust
boundaries are, what leaves the device and to whom, and which hardening is deliberately deferred. For
the vulnerability-reporting process see [../SECURITY.md](../SECURITY.md).

Paths below are relative to `android-app/shared/src/` unless noted.

## Security goals

Local Agent's premise is **on-device by default**. The goals, in priority order:

1. **No silent egress of user content.** The user's conversations, memories, and attached images must
   not leave the device except over a path the user explicitly enabled, and never to an LLM provider on
   the default (local-inference) configuration.
2. **Minimize the required outbound surface.** The only network call needed for the core experience is a
   web search, which carries *only the search query*.
3. **Protect secrets at rest** (API keys, relay identity/credentials) with platform-appropriate
   encryption.
4. **Fail safe.** Optional remote paths fall back to on-device inference when unavailable; missing
   models degrade to no-op rather than crashing or leaking.

What this model does **not** claim: protection against a fully compromised host/OS, a malicious user
inspecting their *own* device's app data with root, or the security of third-party servers the user
chooses to point the app at.

## Trust boundaries

| Boundary | Trusted side | Untrusted / lower-trust side | Enforcement |
|---|---|---|---|
| On-device core ↔ network | The local agent process | Brave, HuggingFace, remote LLM, relay | Egress is enumerated + gated (below) |
| LLM backend seam | Agent loop | Any inference backend (incl. remote/relay) | `inference/InferenceEngine` — backend chosen once per load by `RoutingInferenceEngine`; never special-cased above the seam (invariant #44) |
| Desktop job execution | The desktop user who defines a job | The job's subprocess + its arguments | Positional shell-arg binding in `job/JobExecutor` (no injection); 300 s timeout; 8 KB output cap |
| Job definitions across sync | The desktop (authoritative) | A paired mobile peer | `sync/JobSyncPolicy` — `DesktopJobSyncPolicy` is **fail-closed** (invariants #49–#51) |
| Job library on disk | The bundled `agent-jobs.zip` | A crafted archive entry | Zip-Slip guard in `job/DesktopJobLibraryStore.resolveSafely` (#67) |
| Relay peer link | The paired device | The relay operator / network | X25519 E2EE; the gateway is zero-knowledge (relays ciphertext) |
| Stripe checkout callback | The desktop app | The browser redirect | Loopback-only (`127.0.0.1`) one-time claim code on `DesktopLinkServer` |

### Jobs: command injection & the sync trust boundary

Desktop jobs run subprocesses (`desktopMain/.../job/JobExecutor.kt`). The hidden settings args and the
user-supplied keyword are passed as **distinct positional shell parameters**, never concatenated into
the script — `buildArgv` emits `sh -c '<command> "$@"' sh <args…> <prompt>` (POSIX) so the arguments
are not re-tokenized and shell metacharacters in user input can't inject commands.

Across sync, **only the desktop may create, edit, or delete a job.** `DesktopJobSyncPolicy`
(`commonMain/.../sync/JobSyncPolicy.kt`) drops remote inserts and tombstones; a mobile peer may only
toggle `paused` and trigger run-now (an imperative `RUN_JOB` relay call guarded by the desktop). This
is fail-closed: anything not explicitly allowed is rejected (invariants #49–#51).

The `agent-jobs` manifest (`init`/`run` commands) is treated as **trusted bundled content** — it ships
in the build and runs full shell strings without injection-safe binding (unlike user keywords). The
trust assumption is build-process + code-review integrity; there is no per-job signature (a deferred
hardening, below). The library archive is extracted with a Zip-Slip guard that rejects any entry
resolving outside the target dir.

## Data at rest

| Data | Where | Protection |
|---|---|---|
| **Secrets** (Brave/Ollama/HF keys, relay account secret, relay X25519 identity, pairing state) | Android: `EncryptedSharedPreferences` (`platform/SecureStorage.android.kt`); desktop: PKCS#12 `secrets.p12` (`platform/SecureStorage.desktop.kt`) | **Encrypted.** Android master key `AES256_GCM` (Android Keystore-backed), keys `AES256_SIV` / values `AES256_GCM`, pref file `local_agent_secure_prefs`. The X25519 relay private key additionally uses an `EncryptedFile` under a hardware-backed Keystore master key (androidMain). |
| **Chat history + attached images, memories, search cache, jobs** | On-device SQLite (`local_agent.db`) via SQLDelight | **Not encrypted at rest** — plaintext SQLite in the app-private data dir. See "Known risks". |

**Desktop secret-store tier.** The PKCS#12 store is password-protected, but the password is derived
from a stable per-user value (or the `LOCALAGENT_KEYSTORE_PASSWORD` override) rather than an
OS-protected secret. As the class doc states, it "defends against casual disk inspection, not an
attacker who has both the file and the user identity." The hardening upgrade is OS-keyring integration
(Secret Service / Keychain / Windows Credential Manager), deferred to keep the increment dependency-free
and headless-safe.

**Android app-private storage** is OS-sandboxed; on a non-rooted device other apps can't read the DB.
On a rooted device or a stolen, unencrypted disk, the plaintext DB is readable.

## Network egress

Every way data leaves the device, and what goes with it:

| Destination | When | What is sent | Encryption | Gating |
|---|---|---|---|---|
| **Brave Search API** | A turn needs the web (classifier fires, or an explicit `web search …`) | **Only the search query** (never the conversation) | HTTPS, `X-Subscription-Token` | Search enabled **and** a Brave key present; fully disableable |
| **Remote LLM** (Ollama / OpenAI-compatible) | Only if the user configures one **and** it's reachable | The **full prompt + chat history** (and the current image, if any) | HTTPS for the OpenAI path; cleartext allowed for LAN Ollama (below) | Off by default; `RoutingInferenceEngine` falls back to on-device when inactive/unreachable |
| **Model downloads** | First run / on demand | Download request (+ HF token for the gated Gemma repo) | HTTPS | Gemma (HuggingFace), `llama-server` binary (pinned `b9478`), ONNX aux models, Vosk |
| **Firebase Analytics + Crashlytics** (Android) | Opt-in only | Aggregate **counters only** (analytics) + **redacted** crash non-fatals | HTTPS | `TELEMETRY_OPT_IN`, default **OFF**; also requires `google-services.json` to be present |
| **Sentry** (desktop) | Opt-in only | Redacted crash non-fatals | HTTPS | `SENTRY_DSN` env + consent |
| **Secure Gateway relay** (anywhere access) | Paid subscription active + paired | E2EE link frames (chat + sync) | WSS + **X25519 (libsodium) E2EE** | Subscription active; relay operator sees only ciphertext |
| **Stripe checkout** (desktop) | User starts a subscription | Browser-driven payment (loopback one-time claim code returns to the app) | HTTPS | User-initiated |

**On the remote-LLM path:** enabling a remote chat model is an explicit user choice that *does* send
conversation content to that server — by design (BYOK, typically a self-hosted Ollama on the LAN). The
classifier, embedder, search, and memory always stay on-device regardless (invariant #44). Cleartext
HTTP is permitted **only** to reach a user-entered LAN Ollama server (`network_security_config.xml`,
`base-config cleartextTrafficPermitted="true"`); all other traffic (Brave, HF, downloads) is HTTPS. The
LAN MITM risk this opens is the user's to accept for their own network.

**Relay trust model:** the gateway is a zero-knowledge relay — pairing establishes an X25519 shared
secret between the two devices, and the relay forwards only ciphertext. The desktop embeds its account
secret in the relay QR so the (subscription-less) phone can authenticate; that secret and the relay
identity key live in encrypted storage and are cleared on unpair. A `4004` REVOKED close is treated as
"subscription ended" → fall back to on-device. Full design: [ANYWHERE_ACCESS_PLAN.md](ANYWHERE_ACCESS_PLAN.md).

## Redaction & telemetry contract

- **No user text in crash reports.** All crash reporting goes through the `observability/SafeCrashReporter`
  facade (`FirebaseSafeCrashReporter` on Android, `SentrySafeCrashReporter` on desktop, `NoOp` in
  tests). Exceptions are wrapped in `RedactedThrowable` (stack + class preserved, message scrubbed);
  breadcrumbs and custom keys are run through `observability/ContentRedactor` (strips Authorization /
  Bearer / `X-Subscription-Token` / query strings). Firebase Crashlytics has **no `beforeSend` hook**,
  so redaction is enforced at every call site — direct `recordException` is a contract violation
  (invariant #24). Reports only upload when telemetry consent is on.
- **Telemetry is counters-only.** `telemetry/TelemetryPayloadBuilder` reads **only** aggregate counter
  tables (invocation counts, latency percentiles, memory headroom, thermal status) and must never read
  the `memories` or `messages` tables. This is asserted by a load-bearing canary test
  (`TelemetryPayloadBuilderTest`) that seeds a unique marker into those tables and fails if it appears
  in any payload. Counter telemetry is a separate channel from the text-aware diagnostic loggers; the
  two are never bridged (invariant #27).
- **Diagnostic logging.** Search queries and (on debug builds) prompts are logged to logcat as an
  on-device debug aid. logcat is privileged-read on a standard device, but on a compromised/rooted
  device this is readable — treat verbose diagnostic logging as debug-only.

## Android permissions

From `androidApp/src/main/AndroidManifest.xml`:

| Permission | Purpose |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Outbound search / downloads / optional remote paths |
| `FOREGROUND_SERVICE` (+ `SPECIAL_USE`, `DATA_SYNC`, `MEDIA_PLAYBACK`) | On-device inference while the user waits; model download; alarm ringtone |
| `POST_NOTIFICATIONS` | Download progress, job-completion + alarm notifications (requested at runtime, API 13+) |
| `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE` | Clock/alarm feature (install-granted; re-arm after reboot) |
| `RECORD_AUDIO` | Continuous in-app dictation (requested at runtime) |
| `CAMERA` (not required) | Scanning the desktop relay pairing QR (requested at runtime) |

No location permission is requested — weather resolves the city from the query/text at query time, not
from device GPS.

## Input-validation safeguards

- **Job command injection** — prevented by positional shell-arg binding (`JobExecutor.buildArgv`).
- **Zip-Slip** — `DesktopJobLibraryStore.resolveSafely` rejects archive entries that canonicalize
  outside the extraction root.
- **Stripe callback** — the `/subscribe/callback` route on `DesktopLinkServer` is loopback-only
  (`127.0.0.1`) and consumes a one-time claim code.
- **Untrusted streamed responses** (remote LLM, relay frames) are parsed with error handling and never
  `eval`'d; the relay frame layer runs only on the trusted device after E2EE decryption.

## Known risks & deferred hardening

These are accepted trade-offs for the current phase (consistent with PRD §4.4), not oversights:

1. **Database not encrypted at rest.** Chat history, memories, and attached images are plaintext SQLite.
   Risk surfaces only with filesystem access (rooted Android, stolen unencrypted disk). Secrets are
   encrypted separately. DB-at-rest encryption is deferred to a later phase.
2. **Desktop secret store is password-tier, not OS-keyring.** Defends against casual inspection, not an
   attacker holding both the `secrets.p12` file and the user identity. OS-keyring integration is the
   planned upgrade.
3. **Remote-LLM operator trust.** When the user enables a remote chat model, conversation content goes
   to that server. This is BYOK by design; the user owns that server's security (and its LAN if
   cleartext).
4. **agent-jobs bundle integrity** rests on build-process + review, not a cryptographic signature.
5. **On-device diagnostic logging** of queries/prompts is readable on a compromised device.

## Reference: key files

- Secrets: `platform/SecureStorage.android.kt`, `platform/SecureStorage.desktop.kt`,
  `androidMain/.../link/transport/AndroidKeystoreKeyStore.kt`
- Egress: `search/KtorBraveSearchClient.kt`, `search/KtorBraveLlmContextClient.kt`,
  `inference/OllamaInferenceEngine.kt`, `inference/RoutingInferenceEngine.kt`,
  `subscription/` + `link/`, `androidApp/.../res/xml/network_security_config.xml`
- Redaction/telemetry: `observability/SafeCrashReporter.kt`, `observability/ContentRedactor.kt`,
  `telemetry/TelemetryPayloadBuilder.kt`
- Jobs: `desktopMain/.../job/JobExecutor.kt`, `desktopMain/.../job/DesktopJobLibraryStore.kt`,
  `commonMain/.../sync/JobSyncPolicy.kt`
- Permissions: `androidApp/src/main/AndroidManifest.xml`

For the invariants these enforce, see [../CLAUDE.md](../CLAUDE.md) (#18, #24–#28, #40, #44, #49–#56,
#67).
