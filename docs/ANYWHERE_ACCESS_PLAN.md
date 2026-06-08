# Paid "anywhere access" — relay subscription (PR #74)

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
