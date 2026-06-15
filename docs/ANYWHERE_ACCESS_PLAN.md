# Paid "anywhere access" — relay subscription (PR #74)

> **PR #80 — LAN link removed (read this first).** The original LAN link (PR #57:
> `magent://` QR, plain-HTTP link server, LAN IP discovery) is **gone** on both
> platforms; the Secure Gateway **relay is now the only** mobile↔desktop pairing
> path. Consequences: the desktop pairing QR appears **only while a subscription is
> active** and carries **only** the relay payload (no `magent://` fallback); the
> mobile **Jobs icon** shows only when paired; relayed chat still routes through the
> desktop's `RoutingInferenceEngine`, so a remote LLM configured on the desktop
> serves the turn. Throughout the historical sections below, **ignore every claim
> that "the LAN path stays intact / is the offline-or-revoked fallback"** — when a
> subscription lapses or the relay drops, the phone now falls back to its **on-device
> model**, not to LAN. Deleted: `LanLinkTransport`, `LinkPairingPayload`,
> `LinkAccessMode`, `LanAddress`, `DesktopLinkClient`; `DesktopLinkServer` keeps only
> the loopback `/ping` + `/subscribe/callback`. See CLAUDE.md invariant **#56**.

Lets a desktop user buy a monthly **Secure Gateway** relay subscription so the
phone reaches the desktop from **anywhere**, instead of LAN-only (PR #57). Spans
two repos: this client + **secure-gateway PR #6** (the Stripe Checkout +
claim-token endpoints). See CLAUDE.md invariant **#54**.

## Scope of this PR (and what's deferred)

**In** — the payment + account half:
- `subscription/` package (`:shared`): `SubscriptionPreferences`/`SubscriptionState` (+`NoOp`),
  `DesktopSubscriptionPreferences` (`subscription_prefs.json`), `RelayGatewayClient`
  (start/claim/status HTTP), `RelaySubscriptionService` (checkout + fallback claim
  poll + launch refresh), `SubscriptionUiController` (+`NoOp`/desktop impl).
- `SecureStorageKeys.RELAY_ACCOUNT_SECRET` — the account secret lives in the OS
  keystore; non-secret ids/status in `subscription_prefs.json`. **Never the DB.**
- `/subscribe/callback` route on the existing `DesktopLinkServer` (browser-facing,
  loopback-only) → claims the credential.
- Launch-time `GET /v1/subscription` in `Main.kt` (only when an account exists).
- Settings "Mobile Agent Connection" desktop section: copy flips to "…anywhere…"
  + a "Subscription Settings" link when active, else "Upgrade to anywhere connection".
  "Subscription Settings" opens the Stripe Customer Portal via
  `RelayGatewayClient.billingPortalUrl` → `POST /v1/billing-portal`.
- SDK `com.securegateway:java` via `mavenLocal()`, wired into `:shared/desktopMain` only.

**Deferred to the transport follow-up PR** (unusable without the relay data path):
- Rendering the relay QR instead of the LAN `magent://link` payload (B4).
- Mobile relay-QR detection + pairing over the relay (C) — needs `sdk/android`
  + lazysodium-android (CLAUDE.md #40).
- Tunneling sync + inference over the E2EE relay websocket.

`RelayLinkTransport` is the documented stub hook (mints the relay QR via the SDK;
data path is `TODO`); it is **not** wired into routing, so the LAN path is untouched.

## Config (desktop, env — draft; not a Settings field yet)

| Env var | Purpose |
|---|---|
| `MOBILEAGENT_GATEWAY_URL` | Secure Gateway auth-service base URL (e.g. `http://127.0.0.1:8080`). Empty ⇒ the upgrade link is hidden. |
| `MOBILEAGENT_SUBSCRIPTION_PORTAL_URL` | **Optional fallback** for "Subscription Settings". Normally the desktop mints a fresh Stripe Customer Portal URL via the gateway (`POST /v1/billing-portal`); this static URL is only used if that call fails. |

Gateway side needs `AUTH_STRIPE_PRICE_ID`, `AUTH_STRIPE_SECRET_KEY`,
`AUTH_STRIPE_WEBHOOK_SECRET`, `AUTH_PUBLIC_URL` (success_url base), `AUTH_CLAIM_TTL`
(default 30m). See secure-gateway README "Desktop subscription onboarding".

---

## Local end-to-end test (payment + claim + launch validation)

Verifies the **shipped** half: Upgrade link → Stripe Checkout → claim → credential
stored → status flips → relaunch re-validates. (Relay QR switch, mobile pairing,
and the E2EE tunnel are out of scope here — the transport follow-up.)

Prereqs: Stripe **test mode** account + the [Stripe CLI](https://stripe.com/docs/stripe-cli)
(`stripe login`), Go toolchain, the desktop app build env.

### 1. A test subscription price

```sh
stripe products create --name "Mobile Agent Anywhere (test)"
stripe prices create --unit-amount 500 --currency usd \
  -d "recurring[interval]"=month -d product=<prod_id_from_above>
# → note the price id (price_…). Optionally tag entitlement:
#   -d "metadata[max_pairs]"=1
```

### 2. Run the gateway (memory store, real Stripe test key)

```sh
cd ../secure-gateway
make keys
stripe listen --forward-to localhost:8080/v1/webhooks/stripe   # prints whsec_…
# in another shell, with the whsec_ from `stripe listen`:
AUTH_JWT_ISSUER=https://auth.local \
AUTH_JWT_SIGNING_KEY_FILE=./keys/relay.key.json \
AUTH_STORE=memory AUTH_BACKPLANE=memory \
AUTH_LISTEN_ADDR=127.0.0.1:8080 \
AUTH_PUBLIC_URL=http://127.0.0.1:8080 \
AUTH_STRIPE_WEBHOOK_SECRET=<whsec_from_stripe_listen> \
AUTH_STRIPE_SECRET_KEY=sk_test_… \
AUTH_STRIPE_PRICE_ID=price_… \
AUTH_CLAIM_TTL=30m \
  go run ./cmd/auth
```

### 3. Publish the SDK to mavenLocal (once, and after any SDK change)

```sh
cd ../secure-gateway/sdk && ./gradlew publishToMavenLocal
```

### 4. Run the desktop app pointed at the local gateway

```sh
cd android-app
MOBILEAGENT_GATEWAY_URL=http://127.0.0.1:8080 \
  ./gradlew :desktopApp:run
```

### 5. Walk the flow

1. Open **Settings → Mobile Agent Connection**. With a gateway URL set you'll see
   **"Upgrade to anywhere connection"**.
2. Click it → the default browser opens **Stripe Checkout**. Pay with the test
   card `4242 4242 4242 4242`, any future expiry/CVC/zip.
3. `stripe listen` forwards `checkout.session.completed` → the gateway provisions
   the account/license and marks the claim ready. The desktop's **fallback poll**
   usually claims the credential a moment before the browser returns, so the
   return page typically shows **"Subscription activated…"** directly (the
   `/subscribe/callback` redirect path runs only if the browser wins the race).
   Either way the desktop ends up activated.
4. Back in Settings the section copy flips to **"…connect to this desktop
   **anywhere**…"** and the link becomes **"Subscription Settings"** — which opens
   the **Stripe Customer Portal** (`POST /v1/billing-portal`). **Enable the portal
   once** in the test dashboard: Settings → Billing → Customer portal, else the
   link no-ops / the gateway returns `502`.
5. Verify persistence: the secret is in the OS keystore (not readable here) and
   the ids are in `<app-data>/MobileAgent/subscription_prefs.json` (Linux:
   `~/.local/share/MobileAgent/`).
6. **Relaunch** the app → `Main.kt` calls `GET /v1/subscription`; the section
   stays in the "anywhere" state. Console logs `[Subscription] subscription refreshed: valid`.

### 6. Revocation (optional)

```sh
stripe subscriptions cancel <sub_id>     # → customer.subscription.deleted
```
On next launch (or refresh) `GET /v1/subscription` returns `revoked`; the section
reverts to "Upgrade to anywhere connection". (Live-session cutoff via the relay's
`4004` close code is part of the transport follow-up.)

### Gateway-only check (no UI)

`secure-gateway`'s hermetic E2E already covers the endpoints end to end:

```sh
cd ../secure-gateway
go test ./test/integration/ -run TestDesktopCheckoutClaimFlow -v
```

### Troubleshooting

- **Upgrade link missing** → `MOBILEAGENT_GATEWAY_URL` unset, or you're on mobile.
- **`503 checkout_unavailable`** → gateway missing `AUTH_STRIPE_PRICE_ID` /
  `AUTH_STRIPE_SECRET_KEY` / `AUTH_PUBLIC_URL`.
- **Callback never fires** → the fallback poll still claims via the held nonce;
  watch for `[Subscription] claimed account …`. Confirm `stripe listen` is running.
- **"Subscription Settings" does nothing** → enable the Stripe **Customer portal**
  in the dashboard (test: Settings → Billing → Customer portal). Without it
  `POST /v1/billing-portal` errors and the link no-ops (unless
  `MOBILEAGENT_SUBSCRIPTION_PORTAL_URL` is set as a static fallback).
- **Go not on PATH** → `/home/lawrenceley/.local/go-sdk/go/bin`. Android build →
  `export ANDROID_HOME=/home/lawrenceley/android-sdk`.

---

# Relay transport — tunnel the link over the E2EE relay (PR #75 / secure-gateway PR #7)

The follow-up that delivers the deferred B4/C/T from #54: when a subscription is
active, the mobile↔desktop link (chat inference + bidirectional sync) tunnels over
the Secure Gateway's E2EE relay WebSocket, so the phone reaches the desktop from
anywhere. The LAN path (PR #57) stays intact for non-subscribers and as the
offline/revoked fallback. See CLAUDE.md invariant **#55**.

## As-built

- **One transport seam** — `link/transport/LinkTransport` (commonMain): `unary` +
  `serverStream` of opaque strings, picked by `LinkTransportProvider`. The existing
  wire payloads (OpenAI chat JSON, post-`data:` SSE chunks, `SyncBundle` JSON) ride
  unchanged inside the frames, so `DesktopLinkInferenceEngine` / `LinkSyncClient`
  (renamed from `LinkSyncHttpClient`) / `SyncController` are transport-agnostic.
  - `LanLinkTransport` — today's Ktor HTTP, relocated (LAN behaviour byte-identical).
  - `RelayLinkTransport` (mobile) — frames RPC over a `RelayBytePipe` (the SDK's
    opaque `send`/`onMessage`) via `FrameMultiplexer` (client) ↔ `FrameDispatcher`
    (server). Envelope: `LinkFrame{v,id,kind,method,query,status,body}` — `id`
    correlation; `REQUEST→RESPONSE` (unary) / `REQUEST→STREAM_DATA*→STREAM_END|
    STREAM_ERROR` (stream) + `CANCEL`; each frame `send` awaits the peer ack
    (per-stream backpressure).
- **Desktop = relay request-server** — the route bodies live in ONE shared
  `DesktopLinkRequestHandler : LinkRequestHandler` used by BOTH the Ktor
  `DesktopLinkServer` and the relay `FrameDispatcher`. `Main.kt` runs the relay
  **concurrently** with the LAN server when `subscription.isActive`: mint relay QR →
  `awaitPairing` → `connect` → serve. The published QR shows the relay payload while
  subscribed (wins over the LAN `magent://`), reverting to LAN on lapse/revoke.
  `DesktopRelayHost` (repurposed from the PR #74 `RelayLinkTransport` stub) +
  `DesktopRelayBytePipe` wrap the SDK `DesktopClient`.
- **Mobile** — `AndroidRelayBytePipe`/`AndroidRelayBytePipeFactory` (androidMain)
  wrap `MobileClient` (SDK types stay out of commonMain, #23). `AccessMode`
  (`DesktopLinkConfig.accessMode`/`relayQrJson`) is derived in
  `SettingsViewModel.applyScannedLink` — a relay QR is detected via the commonMain
  `RelayQrPayload` mirror (NOT the SDK type). `DefaultLinkTransportProvider`
  establishes the relay pipe in the background; `current()` returns it only while
  the pipe is UP, else null → on-device fallback. A relay up/down pushes
  `OllamaConnectionMonitor.requestReload()` so the next turn re-decides.
- **Mobile credential** — the phone has no subscription; the desktop embeds its
  account secret in the relay QR (SDK `QrPayload.accountSecret`, injected
  client-side by `generatePairingQr`, read by `MobileClient.pair`). The phone stores
  it in `SecureStorage` (`RELAY_ACCOUNT_SECRET`) and clears it on unpair. A relay
  `4004` (REVOKED) close ⇒ `LinkConnectionState.DISABLED` ⇒ fall back to LAN/local.

## Verification

In-process (no relay/device):
```sh
cd android-app
./gradlew :shared:desktopTest --tests "com.contextsolutions.mobileagent.link.transport.*"
# FrameRoundTripTest — multiplexer↔dispatcher unary + streaming + id correlation
# RelayRoutingTest   — relay-QR detection, config gating, LAN/relay selection
./gradlew :shared:compileKotlinDesktop :androidApp:compileDebugKotlin   # both targets
```

Gateway hermetic E2E (boots the Go relay + auth, runs the Kotlin mobile SDK ↔ Java
desktop SDK through a live relay — exercises the `account_secret` QR flow):
```sh
cd ../secure-gateway/sdk
./gradlew publishToMavenLocal                       # com.securegateway:{core,java,android}:0.1.0
PATH="$PATH:/home/lawrenceley/.local/go-sdk/go/bin" ./gradlew :java:e2eTest
```

Live local run (manual): set `MOBILEAGENT_GATEWAY_URL` (+ `MOBILEAGENT_RELAY_WS_URL`
if not derivable), subscribe on desktop → the relay QR renders → scan on the phone →
both dial `wss /v1/connect` → relay reports `peer_online` → chat streams + sync flow
over the relay. Pull the subscription (`stripe subscriptions cancel`) → `4004` →
transparent fallback to LAN/local.

On-device crypto smoke (proves the real AAR runs on arm64):
```sh
cd android-app
ANDROID_HOME=/home/lawrenceley/android-sdk ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.contextsolutions.mobileagent.relay.RelayCryptoSmokeTest
# RelayCryptoSmokeTest — X25519 derive + XChaCha20-Poly1305 session round-trip + tamper
# detection under lazysodium-android on the device (where lazysodium-java's JNA build can't load).
```

## On-device relay (shipped — secure-gateway PR #8 / mobile-agent PR #76)

The secure-gateway mobile SDK now ships a **real Android AAR** (`com.securegateway:android`,
the `:android-aar` `com.android.library` module on **lazysodium-android**), so relay crypto
runs natively on the device. The plain-JVM `:android` module is retained — unpublished —
solely so the hermetic cross-platform `:java:e2eTest` keeps running on the JVM. Key points:

- **Crypto seam:** `:core/Crypto` types against the base `LazySodium` and resolves the
  concrete binding via a `ServiceLoader` `SodiumProvider` (JVM modules register a
  `LazySodiumJava` provider; the AAR registers a `LazySodiumAndroid` one). `:core`'s
  lazysodium-java is `compileOnly`, so it never leaks onto the Android classpath.
- **JNA native:** `SodiumAndroid` is JNA-based and needs `libjnidispatch.so` (arm64); the
  AAR excludes lazysodium-android's plain-jar `jna` and pulls `net.java.dev.jna:jna:5.17.0@aar`
  so AGP unpacks the native into the APK.
- **16 KB page-size (Play requirement, API 35+):** lazysodium-android is pinned **5.2.0** —
  its `libsodium.so` ELF LOAD segments are 16 KB-aligned (5.1.0 was 4 KB and tripped Android's
  16 KB compatibility check). 5.2.0 transitively needs the Kotlin-2.1 stdlib (via androidx.core-ktx),
  so the `:android-aar` module bumps its Kotlin plugin to 2.1.x — a separate plugin application
  from the JVM modules' kotlin-jvm 1.9.24, which keeps `:java:e2eTest` untouched. Verified on the
  Pixel 7 APK: every native lib (libsodium/libjnidispatch + litert/tflite/datastore/graphics.path)
  is `p_align` 0x4000.
- **Identity at rest:** `AndroidKeystoreKeyStore` (mobile-agent `androidMain`) stores the
  X25519 private key in an androidx `EncryptedFile` under a hardware-backed AndroidKeyStore
  master key. (The Android hardware Keystore can't hold a raw X25519/Curve25519 key directly,
  so the libsodium-generated key is encrypted at rest rather than hardware-resident.)
- **#40 native check (Pixel 7):** the APK packages `libsodium.so` + `libjnidispatch.so`
  alongside `libLiteRt.so` / `libLiteRtClGlAccelerator.so` / `liblitertlm_jni.so` — all
  distinct names, **no collision**, so the litert `pickFirst`/`extractLitertJni` packaging is
  unchanged. Verified on-device: classifier + embedder + Gemma all load; `RelayCryptoSmokeTest`
  passes.

The **full live-relay run** (Stripe subscribe → relay QR → camera scan → chat+sync over the
relay → `4004` revoke fallback) is the manual procedure above; the device-specific risk it
covered (does the arm64 crypto load + round-trip) is now proven by `RelayCryptoSmokeTest`.

The chat-header relay status dot remains deferred (cosmetic — relay chat + sync work without it).

# Relay UX, reconnect & robustness (mobile-agent PR #77 / secure-gateway PR #9)

The follow-up after the relay validated end-to-end on a Pixel 7 — the unpair/disconnect path,
transport-aware status, the on-device SDK fixes found during validation, and the two
reconnect bugs (mobile re-pair-on-reconnect, desktop QR-vanishes-on-restart). All shipped;
the live-relay run (Stripe subscribe → relay QR → camera scan → chat+sync → revoke fallback)
remains the manual procedure above.

## Reconnect without re-pairing (the load-bearing fixes)

The relay QR's **pairing token is single-use** (one `completePairing`). Both sides originally
re-ran the *pairing* flow on every reconnect, replaying the spent token:

- **Mobile — `401 pairing_token_invalid` on toggle/relaunch.** `AndroidRelayBytePipeFactory`
  called `MobileClient.pair()` every time it rebuilt the pipe (Desktop Agent Connection toggled
  off→on, or an app relaunch). Fix: persist `{pairingToken, deviceId, pairId, desktopPublicKey}`
  in `SecureStorage` (`RELAY_PAIRING_STATE`) after a successful pair; on `create()`, if the saved
  token matches **this** QR's token, restore it through the SDK's new
  `MobileConfig.pairId`/`desktopPublicKeyB64` and call `connect()` directly (`MobileClient.isPaired()`
  gates it). A freshly scanned QR (new token, e.g. after a desktop re-mint) no longer matches →
  pairs fresh and overwrites. Mobile Unpair clears the saved pairing (its `pairId` is revoked).
- **Desktop — relay QR vanishes on restart (falls back to the LAN `magent://` QR).** Each launch
  `DesktopRelayHost.newClient()` rebuilt `DesktopClient` without a device id, so
  `generatePairingQr()` registered a **new** `dev_…` every time (the X25519 identity persisted via
  `relay_identity.key`, but the gateway device id did not). The gateway only skips the capacity
  check for a **re-pair on the same desktop device id**; with a new id and the prior pairing still
  holding the account's single `max_pairs` slot, `createPairingToken` returned `capacity_exceeded`,
  the relay arm threw, and `Main.kt` published the LAN QR. Fix: persist the device id
  (`RELAY_DESKTOP_DEVICE_ID`) and restore it into `DesktopConfig.deviceId`, reading it back via the
  SDK's new `DesktopClient.deviceId()`. The re-mint is then a re-pair — capacity check skipped, slot
  + `pair_id` reused — so the relay QR keeps publishing across restarts.

**Desktop — full reconnect-without-re-pairing (PR #91).** Persisting the device id (above) kept the
QR *publishing*, but the desktop still **re-minted a fresh pairing token on every restart** — the QR
changed, so the phone's saved token no longer matched and it had to re-scan (and, until the stale
slot was freed, you had to Unpair first). The fix makes the desktop symmetric with the mobile half:
persist `{pairId, mobilePublicKey}` (`RELAY_DESKTOP_PAIR_ID` / `RELAY_MOBILE_PUBLIC_KEY` in
`secrets.p12`) after a successful pairing, and on each launch try `DesktopRelayHost.reconnect()`
*before* minting a QR. It restores those into the SDK's new `DesktopConfig.pairId`/
`mobilePublicKeyB64`; `DesktopClient.isPaired()` is then true, so it calls `connect()` directly
(issue a fresh connection token for the existing `pair_id`, no new pairing token, **no QR, no
re-scan**). `Main.kt` only falls back to `generatePairingQr()` + `awaitPairing()` when there's no
saved pairing or the reconnect fails because the pair is **dead** — `connect()`'s synchronous
`issueToken` throws `AuthException` with a real HTTP status (revoked/unknown pair), which
`isDeadPairingError` distinguishes from a transient `transport_error` (httpStatus 0, gateway
unreachable → keep the saved pairing and retry). A desktop **Disconnect** and a peer **REVOKED**
both clear the saved pairing (the `pair_id` is gone) but keep `RELAY_DESKTOP_DEVICE_ID`, so the
next loop mints a fresh QR that re-pairs into the same slot. This removes the "Unpair once to free
the slot" desktop-restart workaround. Watch for `host: saved pairing found (pairId=…); reconnecting
without re-pairing` then `[Relay] reconnected to existing pairing; serving framed link requests`.

## UX + robustness (relay UX commit)

- **Transport-aware status.** The "Mobile/Desktop Agent Connection" sections name the transport —
  mobile: *"Connected to gateway"* / *"Connected to LAN (host)"*; desktop: *"Phone connected via
  gateway/LAN"* — via `LinkTransportProvider.relayState` + `DesktopLinkConnectionStatus.connectionKind`
  (`MobileLinkKind`); the LAN `/health` poll can't see the relay.
- **Unpair / Disconnect through the gateway.** `RelayDisconnector` seam (commonMain): mobile
  `RelayUnpairDisconnector` revokes via the live `MobileClient`; desktop `DesktopRelayHost` revokes
  then re-arms a fresh QR. `SettingsViewModel` branches `unpairDesktop()`/`disconnectMobileDevice()`
  on the connection kind. Desktop re-arm on peer revoke (`REVOKED→DISABLED`) cancels its watcher
  before its own unpair to avoid a double re-arm.
- **Crash-safe pipe.** `AndroidRelayBytePipe` drops sends after close (pairs with the gateway
  `ConnectionManager.close()` crash fix in #9).

## SDK + gateway fixes (secure-gateway PR #9)

- `AuthClient` HTTP on `java.net.HttpURLConnection` (not `java.net.http.HttpClient`, absent on
  Android → was `NoClassDefFoundError` on-device); `MobileClient.unpair()`/`DesktopClient.unpair()`;
  crash-safe `ConnectionManager.close()`; `MobileClient.isPaired()`/`deviceId()`/`desktopPublicKeyB64()`
  + `DesktopClient.deviceId()` for the reconnect/restart fixes above.
- **Billing:** checkout-completion reuses the customer's existing account when
  `customer.subscription.created` arrived before `checkout.session.completed`, fixing a
  claim/license mismatch (`404 license_not_found`).

## Local test-setup requirements (learned the hard way)

- **Redis shared backplane is REQUIRED** for unpair/revocation to propagate auth → relay: separate
  `memory` backplanes don't talk, so the desktop only ever sees `PEER_OFFLINE`, never `REVOKED`.
  Run `redis-server` and set `AUTH_BACKPLANE=redis AUTH_REDIS_ADDR=127.0.0.1:6379` +
  `RELAY_BACKPLANE=redis RELAY_REDIS_ADDR=127.0.0.1:6379`.
- QR endpoints must be the desktop's **LAN IP**, not `127.0.0.1` (the phone can't reach the
  desktop's loopback). `AUTH_PUBLIC_URL` + `AUTH_RELAY_URL` must use the LAN IP; `AUTH_JWT_ISSUER`
  == `RELAY_JWT_ISSUER`. With `AUTH_STORE=memory`, an auth restart wipes accounts → clear the
  desktop subscription + re-claim.
- Don't run `connectedAndroidTest` casually — it can wipe the phone's relay pairing/app data; use
  `installDebug`.

> **Diagnostics retained for UAT.** The `RELAY_ONLY_DEBUG` relay-pin in
> `DefaultLinkTransportProvider` has been **removed** — the normal LAN fallback is restored
> (`current()` returns the relay only when `accessMode == RELAY` *and* the pipe is UP, else LAN
> /on-device). The `[sdk]`/`pipe:`/`select:`/`[Relay/scan]` diagnostic logging is **kept** for
> ongoing user-acceptance testing on main; strip it once UAT signs off. `RelayCryptoSmokeTest` stays.

## Gotcha — `bad_devices` 400 on pairing → no QR (PR #80)

**Symptom (desktop log):** `host: minting relay pairing QR …` then
`anywhere-access UNAVAILABLE … cause: auth service returned 400 (bad_devices)`, and **no QR
renders** in Settings.

**Cause.** The desktop persists its gateway device id (`RELAY_DESKTOP_DEVICE_ID`, in
`secrets.p12`) and restores it into `DesktopConfig.deviceId` so a restart re-pairs into the same
`max_pairs` slot (#55). The SDK's `DesktopClient.ensureDevice()` only registers a device when the
id is `null`, so a **stale** saved id is reused verbatim. If that id belongs to a *prior* account
or gateway run — you **re-subscribed under a new `acct_…`**, or the gateway was restarted with
`AUTH_STORE=memory` (which wipes its device table) — the gateway's `deviceForRole(…, RoleDesktop)`
finds nothing and `createPairingToken` returns `400 bad_devices`. Before PR #80 this fell back to
the LAN `magent://` QR, masking the bug; PR #80 removed that fallback, so it now surfaces as "no QR".

**Self-heal (shipped, PR #80).** `DesktopRelayHost.generatePairingQr()` catches `bad_devices`,
clears `RELAY_DESKTOP_DEVICE_ID`, rebuilds the `DesktopClient` (so `ensureDevice` re-registers a
**fresh** device under the current account), and retries the mint once. Watch for:
`host: desktop device id rejected (… bad_devices); clearing + re-registering a fresh device`.
**Only `bad_devices`** self-heals — `capacity_exceeded` (a valid prior pairing legitimately holds
the single slot) is left to the operator (unpair the other desktop / raise `max_pairs` / restart
a memory-store gateway).

**Manual reset alternative.** Stop the desktop, delete
`~/.local/share/MobileAgent/subscription_prefs.json` and the relay entries in `secrets.p12`
(`RELAY_ACCOUNT_SECRET` / `RELAY_DESKTOP_DEVICE_ID` / `RELAY_IDENTITY_KEY` — or just delete
`secrets.p12` to wipe all app secrets), then re-subscribe and it registers fresh.

**Upstream root cause.** `ensureDevice`'s skip-when-non-null lives in the SDK
(`secure-gateway/sdk/java/.../DesktopClient.java`); a future SDK fix could re-register on a
`bad_devices` response so the client recovers without the host-side retry.

## Relay identity key — stored in secrets.p12, not a plaintext file (PR #80)

The SDK's default `FileKeyStore` (`config.keyStoreFile(path)`) persists the desktop's **X25519
identity private key** to `relay_identity.key` as **plaintext hex**, protected only by `chmod 0600`
— flagged during #80 review as an avoidable cleartext private key on disk. `DesktopRelayHost` now
injects a custom `SecureStorageKeyStore` (desktopMain, implements `com.securegateway.core.keystore.KeyStore`)
via `DesktopConfig.keyStore`, which keeps the hex key in the app's encrypted `DesktopSecureStorage`
(`secrets.p12`) under `SecureStorageKeys.RELAY_IDENTITY_KEY` — alongside the account secret + device
id, one credential tier. On first run it **migrates** any existing `relay_identity.key` into the
store and deletes the plaintext file, so a paired desktop keeps its identity (and its phone) without
re-pairing. Covered by `SecureStorageKeyStoreTest`.

**Tier caveat (unchanged posture).** `secrets.p12`'s PKCS#12 password is per-user-derived by default
(or `MOBILEAGENT_KEYSTORE_PASSWORD`), so this matches the account-secret tier — it defends against
casual disk inspection, not a process already running as the user. The genuine hardening is an OS
keyring (Secret Service / Keychain / DPAPI) via the SDK's `OsKeyStore` seam — still deferred.
