# Security follow-ups

Tracks the security-audit findings whose **full** remediation is a cross-repo or
larger-effort item, so the work is not lost when the (intentionally uncommitted)
`docs/SECURITY_AUDIT.md` is gone. PR #16 landed the in-repo mitigations
(L3 docs, L4 plan below, L5 manifest, L6 warning, L2 QR warning); the items below are the
remaining work.

---

## M4 — Move the Secure Gateway SDK off `mavenLocal()` to a signed repo + pin SHAs

**Status: DONE — fully CI-validated (local-agent PR #18).** Step 1 (secure-gateway PR #6) wired
GPG-signed publishing to GitHub Packages; the namespace was then renamed `com.securegateway` →
`com.contextsolutions.securegateway` (secure-gateway PR #7 / local-agent PR #17) and a signed
`0.2.3` release published (signatures verified). Step 2 (local-agent PR #18) swapped
`mavenLocal()` → the signed GitHub Packages repo (content-filtered, `read:packages` auth) and
turned on Gradle dependency verification (`android-app/gradle/verification-metadata.xml`, sha256,
`verify-metadata=true`, ~1395 components) pinning every artifact incl. the SDK. CI
(`prompt-eval-gate.yml`, `desktop-package.yml`) authenticates with the **`SECURE_GATEWAY_PAT`
repo secret** (classic PAT, `repo` + `read:packages`) instead of checking out +
`publishToMavenLocal`.

**Validated green:** `prompt-eval-gate` (Android + verification), `desktop-package` on
**Linux**, local `installRelease` (R8 + lint) on a Pixel 7, and local `installDebug`. The
exposed-during-setup token was rotated. **Deferred:** macOS/Windows `desktop-package`
verification — that workflow runs on `v*` tags (not PRs), so formal cross-OS validation is
deferred to the release-tag run; the macOS/Windows native entries are hand-added in
`verification-metadata.xml` and were exercised once via a manual `workflow_dispatch`. See
CLAUDE.md "Relay SDK from GitHub Packages + dependency verification" for the (battle-tested)
regen procedure — note the lint/release path needs a separate `--no-configuration-cache` pass.

Historical plan (for reference):

**Recommendation: GitHub Packages (Maven registry) under `Context-Solution-Inc`.** Both repos
are already in that org; the package is private by default (the SDK is crypto code — do **not**
publish to Maven Central); GitHub Actions authenticates with `GITHUB_TOKEN`; CI already holds a
cross-repo PAT (`SECURE_GATEWAY_PAT`). GPG-sign the artifacts so Gradle verifies signatures, not
just checksums.

### Step 1 — `secure-gateway`: publish to GitHub Packages (PR in secure-gateway)

1. `sdk/build.gradle.kts` (`subprojects` + `android-aar`): add a
   `publishing.repositories.maven` block →
   `https://maven.pkg.github.com/Context-Solution-Inc/secure-gateway`, credentials from
   `GITHUB_ACTOR` / `GITHUB_TOKEN` (or `gpr.user`/`gpr.key` props for local). Keep
   `publishToMavenLocal` working for SDK co-development.
2. Apply the `signing` plugin; GPG-sign the `maven`/`release` publications (key + passphrase
   from CI secrets). Export the public key for consumers.
3. Bump `version` off `0.2.3` for the first published-remote build (so a stale mavenLocal
   `0.2.3` can't shadow it) and tag the release.
4. New `secure-gateway/.github/workflows/publish-sdk.yml`: on tag/release run
   `./gradlew publish` with `permissions: packages: write`.

### Step 2 — `local-agent`: consume the remote + enable verification (PR after Step 1)

1. `android-app/settings.gradle.kts` (`dependencyResolutionManagement`): replace `mavenLocal()`
   with a `maven { url = "https://maven.pkg.github.com/Context-Solution-Inc/secure-gateway"
   credentials { … } content { includeGroup("com.securegateway") } }` block (creds from
   `GITHUB_ACTOR`/`GITHUB_TOKEN` env or `gpr.*` props). Optionally keep `mavenLocal()` behind a
   `-PuseLocalSdk` opt-in for SDK co-development.
2. Generate `android-app/gradle/verification-metadata.xml` via
   `./gradlew --write-verification-metadata sha256,pgp help`, resolving **both** the android and
   desktop configurations (so platform-specific artifacts are captured). Turn on
   `<verify-metadata>true</verify-metadata>` and add the `com.securegateway` GPG key as trusted
   so signatures are verified. The generated file pins SHA-256 for every dependency.
3. Update `prompt-eval-gate.yml`: drop the SDK checkout + `publishToMavenLocal` steps
   (currently lines 61-75); supply GitHub Packages read creds instead. Verify `desktop-package.yml`
   resolves cleanly.
4. **Caveats:** the verification-metadata file is large and must be regenerated on every
   dependency bump (re-run `--write-verification-metadata`); generate on a matrix covering
   android + desktop or a platform-only artifact fails the build. Document the regen command in
   `CLAUDE.md`.

---

## L2 — Remove the relay account secret from the displayed pairing QR

**Status: DONE — per-pair credential (secure-gateway PR #8 / SDK `0.2.4` / this consumer PR).** The
desktop's shared account secret no longer rides the pairing QR. The gateway mints a **per-pair
credential** at pairing completion and registers the mobile device from its public key (authorized by
the single-use pairing token), so the phone needs no account secret to register *or* to issue tokens:
- **Gateway (secure-gateway PR #8):** `pair_credentials` table (migration `0005`, keyed by `pair_id`,
  rotated on re-pair); `completePairing` mints + returns `pair_credential` and `mobile_device_id`;
  token/unpair endpoints accept the account secret **or** a pair credential (bound to its
  pair/device); unpair revokes it.
- **SDK `0.2.3`→`0.2.4`:** `DesktopClient.generatePairingQr` stops injecting `QrPayload.accountSecret`;
  `MobileClient` learns the credential + device id from `completePairing` and issues/refreshes/unpairs
  with it (`MobileConfig.pairCredential` restores it for reconnect). Cross-platform E2E passes with the
  phone holding no account secret.
- **Consumer (this repo):** bumped `securegateway = 0.2.4` + regenerated `verification-metadata.xml`;
  `SettingsViewModel.applyScannedLink` no longer persists `RELAY_ACCOUNT_SECRET` from the QR;
  `AndroidRelayBytePipeFactory` persists the per-pair credential inside `RELAY_PAIRING_STATE` and feeds
  it back via `MobileConfig.pairCredential` on reconnect; `DESKTOP_LINK_QR_WARNING` softened (the QR now
  carries only a short-lived pairing token). The desktop keeps its own account secret
  (`RELAY_ACCOUNT_SECRET`) for its gateway auth — only the phone/QR exposure is gone.

PR #16's interim QR warning (`DesktopLinkPairingControls.desktop.kt`, `DESKTOP_LINK_QR_WARNING`) is now
the softened note. The original cross-repo design follows (as implemented):

**Why not literal "rotate the account secret":** the account secret is the *shared* account
credential (`acct_<id>.<rand>`), used by the desktop **and** every paired phone. The phone needs
it **before** the E2EE relay channel exists — `MobileClient.connect()` calls
`auth.issueToken(accountSecret, …)` to *get onto* the relay — so it cannot be "delivered over the
established E2EE channel post-pairing" (chicken-and-egg), and rotating the shared secret after
phone A pairs would break a later phone-B pairing.

**Design — per-pair credential** (keeps the shared account secret entirely on the desktop, off
the QR and off the phone):

1. **Gateway (Go):** at pairing completion (`POST /v1/pairings`, already authenticated by the
   single-use pairing token in the QR), mint a per-pair credential scoped to that
   `pair_id`+`mobile_device_id` and return it in the response; store its SHA-256 (mirrors
   `accounts.secret_hash` / `refresh_tokens`). Accept the per-pair credential **or** the account
   secret at the token endpoints (`/v1/token`, `/v1/token/refresh`, `/v1/pairings/unpair`).
   Revoke it on unpair / `PairingRevoked`. One SQL migration.
2. **SDK (core/java/android):** `DesktopClient.generatePairingQr()` stops injecting
   `qr.accountSecret`; `AuthClient.completePairing()` returns the per-pair credential;
   `MobileClient.pair()` stores **that** and uses it for `issueToken`. Keep `accountSecret`
   optional in `QrPayload` for back-compat during rollout.
3. **local-agent consumer:** `RelayQrPayload.accountSecret` becomes unused — stop persisting it
   from the QR (`SettingsViewModel.applyScannedLink`); store the per-pair credential instead
   (new/repurposed `SecureStorageKeys` key). Then drop/soften the Part-A QR warning.

**Sequencing:** gateway → SDK → consumer, behind the optional-`accountSecret` back-compat.

---

## L4 — Migrate off the alpha `androidx-security-crypto`

**Status:** plan only (the audit asks to plan, not implement).

`androidx-security-crypto = 1.1.0-alpha06` (`gradle/libs.versions.toml:18`) is an alpha release
and Google has signalled deprecation of the library. It backs, on Android:

- `EncryptedSharedPreferences` — Brave/HF API tokens
  (`shared/src/androidMain/.../platform/SecureStorage.android.kt`).
- `EncryptedFile` — the relay X25519 identity private key
  (`shared/src/androidMain/.../link/transport/AndroidKeystoreKeyStore.kt`).
- The relay pairing-state blob rides the `SecureStorage` above
  (`AndroidRelayBytePipeFactory`).

**Migration target:** Keystore-direct AES-256-GCM — wrap our own `SharedPreferences` / file with
an `AndroidKeyStore`-held AES key (the same hardware-backed-master-key pattern already used),
**or** Jetpack DataStore + Tink. Scope is those two `androidMain` files plus the pairing-state
serialization; no DB schema change. Provide a one-time read-old/write-new migration so existing
installs keep their tokens + relay identity (no re-pair). Track as a post-M7 item; move when a
stable `androidx.security:security-crypto` (or the chosen replacement) is available.
