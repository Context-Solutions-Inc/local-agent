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

**Status: Phases 1–2 DONE (secure-gateway PR #8); Phase 3 (consumer) pending the signed `0.2.4`
publish.** The gateway now mints a **per-pair credential** at pairing completion and registers the
mobile device from its public key (authorized by the pairing token), and the SDK (bumped
`0.2.3`→`0.2.4`) uses it: `DesktopClient.generatePairingQr` no longer injects
`QrPayload.accountSecret`, and `MobileClient` issues/refreshes/unpairs with the per-pair credential
(account-secret fallback only against a legacy gateway). The cross-platform E2E now passes with the
phone holding **no** account secret. **Remaining (Phase 3, this repo):** after a signed `0.2.4` is
published, bump `securegateway` in `libs.versions.toml` + **regen `verification-metadata.xml`**
(full M4 dance — the SDK hashes change), stop persisting `RELAY_ACCOUNT_SECRET` from the QR in
`SettingsViewModel.applyScannedLink`, persist the per-pair credential in `AndroidRelayBytePipeFactory`'s
`SavedPairing` (feed back via `MobileConfig.pairCredential` on reconnect), and drop/soften
`DESKTOP_LINK_QR_WARNING`. PR #16's QR warning remains the interim mitigation until then.

PR #16 added a "don't screenshot/share this code" warning under the QR
(`DesktopLinkPairingControls.desktop.kt`, `DESKTOP_LINK_QR_WARNING`). The original cross-repo design
follows (now implemented in Phases 1–2):

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

**Status: DONE — Keystore-direct AES-256-GCM, clean break (local-agent PR #19).**

`androidx-security-crypto = 1.1.0-alpha06` (an alpha Google has deprecated) is **removed** —
dependency, version catalog entry (`gradle/libs.versions.toml`), and the pinned
`verification-metadata.xml` component all deleted. Android at-rest secrets now ride a **Keystore-direct
AES-256-GCM envelope**: one hardware-backed `AndroidKeyStore` AES-256 key
(`KeystoreAesKey`, alias `local_agent_secure_store_v1`) seals every value via `AesGcmEnvelope`
(`iv ‖ ciphertext+tag`, fresh random IV per write, 128-bit tag):

- `AndroidSecureStorage` (`SecureStorage.android.kt`) — values Base64 in a **plain** SharedPreferences
  file `local_agent_secure_store` (Brave/HF/Ollama keys, the M1 SQLCipher DB key, relay credentials).
  Key *names* are plaintext (not sensitive); only values are encrypted.
- `AndroidKeystoreKeyStore` (`link/transport/`) — the relay X25519 identity sealed to
  `relay_identity.x25519.gcm` under the same key.

**Clean break — no read-old migration** (chosen because closed-beta hasn't started and PR #94 already
forced fresh installs). Existing dev/test installs lose API keys, re-pair, and lose their encrypted
DB. `CleanBreakReset` (invoked at the top of `AndroidDatabaseFactory.create`, before the M1
keystore-loss guard) prevents an in-place upgrade from *bricking*: when a legacy androidx artifact is
present **and** the new store has no DB key, it deletes the legacy
`local_agent_secure_prefs.xml`/`relay_identity.x25519.enc` and wipes the orphaned encrypted DB so a
fresh key + DB are minted. It only deletes files (never reads androidx), so the dependency fully goes
away; the loud loss-guard still protects against a genuine future Keystore loss. Covered by
`AesGcmEnvelopeTest` + `CleanBreakResetTest` (JVM, `:androidApp` unit).
