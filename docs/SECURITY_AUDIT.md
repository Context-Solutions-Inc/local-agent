# Security Audit — local-agent

| | |
|---|---|
| **Original audit** | 2026-06-21 · commit `8038f88` (branch `main`) |
| **Status update** | 2026-06-26 · commit `47cbc11` (branch `main`) — all findings re-checked against current source |
| **Auditor** | Automated source review (Claude Code) |
| **Scope** | Full codebase: `:shared` (commonMain/androidMain/desktopMain), `:ui`, `:androidApp`, `:desktopApp`, build/Gradle config, AndroidManifest. Excludes the upstream `secure-gateway` SDK internals (reviewed only at the integration seam) and third-party model weights. |
| **Method** | Static review of secret handling, cryptography, relay pairing/transport, subprocess execution, archive/download integrity, mobile↔desktop sync trust boundary, platform/manifest config, telemetry/redaction, and dependency/build security. Findings verified against source; line numbers cited. |

> This report was kept uncommitted while findings were open; it is committed now that **all findings
> are remediated** (see §0) — a record of what was found and fixed.

---

## 0. Status update — 2026-06-26

Every Medium and Low finding from the original audit has now been remediated. The two larger
cross-repo items tracked in `docs/SECURITY_FOLLOWUPS.md` (M4, L2) and the alpha-crypto item (L4) are
done; the quick-win hardening (M2/M3/M5/L1/L3/L5/L6) and the datastore encryption (M1) landed earlier.

| ID | Severity | Title | Status |
|----|----------|-------|--------|
| M1 | Medium | Local datastore not encrypted at rest | ✅ **Resolved** — SQLCipher on both platforms (PR #15 + `a3ddfbf`) |
| M2 | Medium | User search queries written to logs | ✅ **Resolved** — query text gated behind a debug/internal flag, off in release (`b31cce0`) |
| M3 | Medium | Relay diagnostic logging leaks endpoints + token; SDK logger unredacted | ✅ **Resolved** — `[Relay/scan]` gated to debug/internal & carries no credentials; SDK logger wrapped in `ContentRedactor` |
| M4 | Medium | Crypto-critical SDK via `mavenLocal()` without integrity pinning | ✅ **Resolved** — signed GitHub Packages + Gradle dependency verification (PR #18) |
| M5 | Medium | Release build falls back to debug keystore | ✅ **Resolved** — config-time fail-fast for `assembleRelease`/`bundleRelease` (`b31cce0`) |
| L1 | Low | Global cleartext traffic enabled | ⚠️ **Mitigated (residual accepted)** — secret domains HTTPS-pinned + UI warning; global cleartext kept by design for user-configured LAN Ollama |
| L2 | Low | Relay account secret embedded in pairing QR | ✅ **Resolved** — per-pair credential (secure-gateway PR #8 / SDK `0.2.4` / consumer PR #20) |
| L3 | Low | Desktop secrets read from process environment | ✅ **Resolved** — documented `/proc` caveat + non-env fallbacks preferred (PR #16) |
| L4 | Low | `androidx-security-crypto` on an alpha release | ✅ **Resolved** — Keystore-direct AES-256-GCM, dependency removed (PR #19) |
| L5 | Low | `ClockBootReceiver` exported | ✅ **Resolved** — `exported="false"`, protected broadcasts only, idempotent re-arm (PR #16) |
| L6 | Low | Legacy plaintext relay-key cleanup best-effort | ✅ **Resolved** — every-launch retry + `deleteOnExit` + escalated warning (`b31cce0`); residual is inherent to the filesystem |

**Regression caught during L4/L2 validation (2026-06-26):** after L2 dropped the account-secret
injection, the SDK's `QrPayload` (plain Jackson, no `NON_NULL`) emitted `"account_secret":null`, and
the consumer's strict `RelayQrPayload` decoder threw on the present-null → the phone reported the
scanned QR "UNRECOGNIZED" and pairing silently failed. Fixed by adding `coerceInputValues = true` to
the decoder (+ regression test) in PR #19. No security weakening — purely a parse robustness fix.

The detailed findings in §3 below retain their original text for the record, each prefixed with its
current status.

---

## 1. Executive summary

local-agent has a **strong baseline security posture**. The design gets the hard things right: the
mobile↔desktop relay is end-to-end encrypted (X25519 + libsodium), the desktop job subprocess path is
**not** vulnerable to command injection (attacker-influenceable input rides distinct positional `$@`
parameters, never re-tokenized), archive extraction is Zip-Slip-guarded, every downloaded binary/model
is SHA-256 + size verified over HTTPS, the desktop's only listening socket is loopback-only, and the
sync trust boundary is fail-closed (a paired phone cannot create/delete desktop jobs).

**No confirmed remote code execution, authentication bypass, or injection vulnerability was found.**

The findings were **hardening gaps and privacy/operational concerns**, not exploitable breaks — and
**all have since been remediated** (see §0). At the original audit they were:

- The local datastore (conversations, memories/PII, jobs) was **plaintext SQLite** — no app-level encryption (M1). *Now SQLCipher-encrypted on both platforms.*
- **User search queries** and **relay diagnostics/partial credentials** were written to logs (M2, M3). *Now gated to debug/internal and redacted.*
- The crypto-critical relay SDK was consumed from **`mavenLocal()` with no checksum/signature pin** (M4). *Now signed GitHub Packages + dependency verification.*
- Release builds **silently fell back to the debug keystore** when signing config was absent (M5). *Now fails fast.*
- Low-severity items around cleartext transport, QR credential exposure, env-var secrets, and an alpha crypto dependency (L1–L6). *All resolved or mitigated.*

| Severity | Count | Resolved | Mitigated (residual) |
|---|---|---|---|
| Critical | 0 | — | — |
| High | 0 | — | — |
| Medium | 5 | 5 | 0 |
| Low | 6 | 5 | 1 (L1) |
| Info / verified-secure | 10 | n/a | n/a |

---

## 2. Findings

| ID | Severity | Title | Status |
|----|----------|-------|--------|
| M1 | Medium | Local datastore not encrypted at rest | ✅ Resolved |
| M2 | Medium | User search queries written to logs | ✅ Resolved |
| M3 | Medium | Relay diagnostic logging leaks endpoints + partial pairing token; SDK logger unredacted | ✅ Resolved |
| M4 | Medium | Crypto-critical SDK consumed via `mavenLocal()` without integrity pinning | ✅ Resolved |
| M5 | Medium | Release build falls back to debug keystore when signing config is absent | ✅ Resolved |
| L1 | Low | Global cleartext traffic enabled; plain-HTTP Ollama is MITM-able on LAN | ⚠️ Mitigated (residual accepted) |
| L2 | Low | Relay account secret embedded in displayed pairing QR | ✅ Resolved |
| L3 | Low | Desktop secrets read from process environment | ✅ Resolved |
| L4 | Low | `androidx-security-crypto` pinned to an alpha release | ✅ Resolved |
| L5 | Low | `ClockBootReceiver` exported | ✅ Resolved |
| L6 | Low | Legacy plaintext relay-key cleanup is best-effort | ✅ Resolved |

---

## 3. Detailed findings

### M1 — Local datastore not encrypted at rest

> ✅ **Resolved.** The SQLDelight database is now opened keyed with SQLCipher on both platforms.
> Android: `AndroidDatabaseFactory` feeds the passphrase into net.zetetic's `SupportOpenHelperFactory`.
> Desktop: `DesktopDatabaseFactory` uses the willena/`sqlite3mc` `SQLiteMCSqlCipherConfig.withKey(...)`.
> The passphrase is a ~256-bit key minted once and held in the secure tier (`DatabaseKeyProvider` →
> Android Keystore-backed store / desktop `secrets.p12`), with a keystore-loss guard that refuses to
> silently re-key over existing encrypted data. (PR #15 + `a3ddfbf`.)

**Severity:** Medium
**Locations:**
- `androidApp/src/main/kotlin/com/contextsolutions/localagent/app/di/AndroidKoinModule.kt` — `LocalAgentDatabase(AndroidSqliteDriver(...))`
- `shared/src/desktopMain/kotlin/com/contextsolutions/localagent/platform/DesktopDatabaseFactory.kt`

**Description (original):** The SQLDelight database was opened with no passphrase/SQLCipher on either
platform. Conversations, extracted memories (which by design hold personal-identity / preference /
professional PII), job definitions, and cached search results were stored as **plaintext SQLite** in
the app data directory.

**Risk (original):** For a product positioned as a *privacy-focused on-device assistant*, at-rest
protection relied entirely on OS-level controls — and **desktop assumes no FDE**, so any same-user
process, a stolen/backed-up disk, or a misconfigured sync tool exposed all conversation history + PII.

**Remediation:** Encrypt the DB with SQLCipher keyed from the existing secure tiers. *(Shipped.)*

---

### M2 — User search queries written to logs

> ✅ **Resolved.** All three query loggers are now gated behind a `logQueries` flag that defaults to
> **false** and is flipped on only for debug/internal builds via DI — release builds never log the
> query text. (`b31cce0`.)
> - `KtorBraveSearchClient.kt` — `if (logQueries) logger("q=\"$query\"")`
> - `KtorBraveLlmContextClient.kt` — `if (logQueries) logger("llm-context q=\"$query\"")`
> - `search/vertical/BraveSiteFilterAdapter.kt` — `if (logQueries) logger("[vertical:$subtype] brave query=\"$effectiveQuery\"")`

**Severity:** Medium
**Locations:** `KtorBraveSearchClient.kt`, `KtorBraveLlmContextClient.kt`, `vertical/BraveSiteFilterAdapter.kt`

**Description (original):** The raw outgoing search query string was logged on every search turn
(logcat on Android; stdout/stderr on desktop).

**Risk (original):** Search queries are among the most sensitive user signals (health, financial,
legal, personal). This contradicted the on-device-privacy posture.

**Remediation:** Drop the query text from the log line, or gate behind an internal-only flag off by
default in release. *(Shipped — gated, default off.)*

---

### M3 — Relay diagnostic logging leaks endpoints + partial pairing token; SDK logger unredacted

> ✅ **Resolved.** (1) `SettingsViewModel.applyScannedLink` gates its `[Relay/scan]` output behind
> `buildConfig.isDebug || buildConfig.isInternalBuild` and now logs only the QR length/version — no
> endpoint URLs, no `pairingToken.take(6)`, no secret status. (2) The SDK logger is wrapped in
> `ContentRedactor` at both wiring sites (`AndroidRelayBytePipeFactory`, `DesktopRelayHost`:
> `logger = { … ContentRedactor.redact(it) … }`) so SDK output is scrubbed before any sink.

**Severity:** Medium
**Locations:** `ui/.../settings/SettingsViewModel.kt`; `androidMain/.../AndroidRelayBytePipeFactory.kt`; `desktopMain/.../subscription/DesktopRelayHost.kt`

**Description (original):** The QR-scan diagnostic printed gateway URLs and the first 6 chars of the
single-use pairing token, and the SDK's own logger was attached with no redaction wrapper.

**Risk (original):** Infrastructure disclosure + needless partial-credential material in logs, and an
unredacted SDK channel that any future SDK version could leak through.

**Remediation:** Remove/internal-gate the diagnostic; wrap the SDK logger in `ContentRedactor`;
disable SDK debug logging in release. *(Shipped.)*

---

### M4 — Crypto-critical SDK consumed via `mavenLocal()` without integrity pinning

> ✅ **Resolved.** The Secure Gateway SDK is now consumed from secure-gateway's **GPG-signed GitHub
> Packages** Maven registry (content-filtered, `read:packages` auth), and **Gradle dependency
> verification is ON** (`gradle/verification-metadata.xml`, sha256, `verify-metadata=true`) pinning
> every artifact incl. the SDK. CI authenticates with the `SECURE_GATEWAY_PAT` secret. (PR #18.)

**Severity:** Medium
**Locations (original):** `settings.gradle.kts` `mavenLocal()`; `gradle/libs.versions.toml` `com.securegateway:*`.

**Description (original):** The relay's entire E2EE security depended on a SDK resolved from the
developer's local Maven cache with no checksum/signature pin.

**Remediation:** Publish to a real signed repository + enable Gradle dependency verification. *(Shipped.)*

---

### M5 — Release build falls back to debug keystore when signing config is absent

> ✅ **Resolved.** `androidApp/build.gradle.kts` now **fails fast at configuration time** when an
> `assembleRelease`/`bundleRelease` (the distribution artifacts) is requested without
> `RELEASE_STORE_FILE` — it throws a `GradleException` rather than silently signing with the public
> debug keystore. The debug-keystore convenience remains only for explicit local on-device
> verification (`installRelease`), never for a distribution build. (`b31cce0`.)

**Severity:** Medium
**Locations:** `androidApp/build.gradle.kts`

**Description (original):** When `secrets.properties` provided no `RELEASE_STORE_FILE`, the release
`signingConfig` fell back to `~/.android/debug.keystore` — so a CI/automated path could produce a
debug-signed "release" APK, silently.

**Remediation:** Fail fast for the distribution artifacts when a real keystore is missing. *(Shipped.)*

---

### L1 — Global cleartext traffic enabled; plain-HTTP Ollama is MITM-able on LAN

> ⚠️ **Mitigated — residual accepted.** `network_security_config.xml` keeps a global
> `cleartextTrafficPermitted="true"` **by design** so a user can point the chat LLM at a bare
> `http://` Ollama box on their LAN, but it **pins the fixed secret-bearing hosts to HTTPS-only** via
> `<domain-config cleartextTrafficPermitted="false">` (`api.search.brave.com`, `huggingface.co`,
> `github.com`, `*.githubusercontent.com`), and the Remote-LLM settings screen now shows an explicit
> **HTTP warning** (`SETTINGS_OLLAMA_HTTP_WARNING`) when the configured Ollama connection would be
> cleartext. The code also refuses to send an API key over cleartext. The residual (a user who points
> at an `http://` Ollama on a hostile network) is an informed, user-driven choice. (`a3ddfbf` / `b31cce0` / #56.)

**Severity:** Low
**Locations:** `androidApp/src/main/res/xml/network_security_config.xml`; `AndroidManifest.xml`; `ui/.../settings/SettingsScreen.kt`; `OllamaClient`/`OllamaInferenceEngine`.

**Description (original):** Cleartext was globally enabled; model-discovery + chat completions to a
user-configured HTTP Ollama host traveled in clear.

**Remediation:** Per-domain cleartext + a settings warning + prefer SSL. *(Per-domain HTTPS pinning
for fixed hosts + UI warning shipped; global cleartext intentionally retained for LAN Ollama.)*

---

### L2 — Relay account secret embedded in displayed pairing QR

> ✅ **Resolved.** The desktop's shared account secret **no longer rides the QR.** The gateway now
> mints a **per-pair credential** at pairing completion (and registers the mobile device from its
> public key, authorized by the single-use pairing token), and the phone authenticates token
> issue/refresh + unpair with that credential. `DesktopClient.generatePairingQr` stopped injecting
> `QrPayload.accountSecret`; the consumer stopped persisting `RELAY_ACCOUNT_SECRET` from the QR and
> persists the per-pair credential in `RELAY_PAIRING_STATE`. The account secret stays entirely on the
> desktop. The QR now carries only a single-use, ~300 s pairing token + public keys, so the
> screenshot/shoulder-surf exposure is gone; the prior PR #16 warning is softened accordingly.
> (secure-gateway PR #8 / SDK `0.2.4` / consumer PR #20.)

**Severity:** Low
**Locations (original):** `link/transport/RelayQrPayload.kt`; `subscription/DesktopRelayHost.kt`.

**Description (original):** To give the phone (which has no subscription) a credential, the desktop
embedded its persistent account secret in the on-screen pairing QR.

**Remediation:** Carry only a short-lived pairing token in the QR and mint a per-pair credential
post-pairing rather than exposing the account secret. *(Shipped.)*

---

### L3 — Desktop secrets read from process environment

> ✅ **Resolved.** `docs/DESKTOP_PACKAGING.md` now carries a **"Secrets & `/proc` exposure (Security
> L3)"** section documenting that a process environment is world-readable by the same user via
> `/proc/<pid>/environ`, inherited by children, and captured in crash dumps / `systemctl show` /
> audit logs — and that env vars are the *least* private option. All three secrets
> (`LOCALAGENT_KEYSTORE_PASSWORD`, `BRAVE_API_KEY`, `HF_AUTH_TOKEN`) have **non-env fallbacks the app
> prefers** (encrypted `secrets.p12` / OS keyring / `0600` file), with a documented preference order
> for headless deployments. (PR #16.)

**Severity:** Low
**Locations:** `KeystorePassword`, desktop Brave/HF providers; `docs/DESKTOP_PACKAGING.md`.

**Description (original):** Several desktop secrets were sourced from environment variables (not
logged, but `/proc`-visible to the same user and often captured by service managers).

**Remediation:** Prefer file-based/secure-store secrets for headless deployments and document the
`/proc` caveat. *(Shipped.)*

---

### L4 — `androidx-security-crypto` pinned to an alpha release

> ✅ **Resolved.** Migrated off the deprecated alpha `androidx.security:security-crypto` to
> **Keystore-direct AES-256-GCM** — one hardware-backed `AndroidKeyStore` AES key (`KeystoreAesKey`)
> seals every value via `AesGcmEnvelope` for both `AndroidSecureStorage` (the KV store) and
> `AndroidKeystoreKeyStore` (the relay X25519 identity, `relay_identity.x25519.gcm`). The dependency
> is removed from the catalog, build script, and `verification-metadata.xml`. Clean break (no read-old
> migration) with a `CleanBreakReset` guard so an in-place upgrade re-pairs/re-keys instead of bricking
> on the M1 keystore-loss guard. (PR #19.)

**Severity:** Low
**Location (original):** `gradle/libs.versions.toml` — `androidx-security-crypto = 1.1.0-alpha06`.

**Description (original):** The library backing `EncryptedSharedPreferences`/`EncryptedFile` (relay
pairing state, account secret, DB key, and identity key on Android) was an alpha build that Google has
signaled deprecation for.

**Remediation:** Move to a stable replacement; evaluate Keystore-direct / DataStore+Tink. *(Shipped —
Keystore-direct AES-256-GCM.)*

---

### L5 — `ClockBootReceiver` exported

> ✅ **Resolved.** `ClockBootReceiver` is now `android:exported="false"` in the manifest (comment
> "Security L5: not exported"). It listens only for protected broadcasts
> (`BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` — only the OS can send these),
> reads no intent extras, and performs only the idempotent `clockService.rearmAll()`. (PR #16.)

**Severity:** Low
**Location:** `androidApp/src/main/AndroidManifest.xml`; `app/service/clock/ClockBootReceiver.kt`.

**Description (original):** The boot receiver was exported, widening the attack surface (another app
could send a matching intent).

**Remediation:** Confirm the receiver ignores extras and does only idempotent re-arm; un-export.
*(Shipped — un-exported + extras ignored.)*

---

### L6 — Legacy plaintext relay-key cleanup is best-effort

> ✅ **Resolved.** `SecureStorageKeyStore.purgeLegacyFileIfPresent()` now runs on **every launch**
> (re-attempting deletion of any leftover plaintext `relay_identity.key` until it succeeds), escalates
> the failure to a **SECURITY WARNING** log line (not a quiet info line), and registers a
> `deleteOnExit()` JVM-exit cleanup as belt-and-suspenders. The encrypted store is already the source
> of truth, so this only closes the migration window. (`b31cce0`.) The residual (a file the OS refuses
> to delete due to permissions/locks) is inherent to the filesystem, now loudly surfaced.

**Severity:** Low
**Location:** `shared/src/desktopMain/.../subscription/SecureStorageKeyStore.kt`.

**Description (original):** On first launch the keystore migrated the legacy plaintext
`relay_identity.key` into encrypted storage and deleted the file; if `Files.delete` failed it only
logged a warning once, so the plaintext private key could persist.

**Remediation:** Re-attempt deletion on each launch until it succeeds; surface a more visible warning.
*(Shipped.)*

---

## 4. Verified-secure controls (positive findings)

These were specifically checked and found correct — documented so the report is not misread as
"only the listed areas were examined." (Re-confirmed 2026-06-26.)

1. **Job command injection — mitigated.** `JobExecutor.buildArgv` passes the desktop-trusted `command` directly but routes attacker-influenceable `args`/`prompt` (incl. the `RUN_JOB_INLINE` peer keyword) as distinct POSIX positional `$@` parameters (and doubled single-quoted PowerShell literals on Windows) — never re-tokenized. Covered by `JobExecutorTest`.
2. **Redaction facade.** `SafeCrashReporter`/`RedactedThrowable`/`ContentRedactor` scrub messages before Crashlytics; direct `recordException` is a contract violation; telemetry counters are a separate channel from text loggers. No user text in exception messages.
3. **Archive extraction — Zip-Slip guarded.** `DesktopJobLibraryStore` resolves entries via canonical-path containment; the job-init manifest is trusted bundled content.
4. **Download integrity.** Model, llama-server, aux-model, and Node downloads are SHA-256 + size verified before use and delete partial/mismatched files. Node also rejects non-HTTPS/non-loopback mirrors and pins per-asset SHA-256.
5. **Desktop network exposure — minimal.** The only listening socket binds to `127.0.0.1` (`/ping`, `/subscribe/callback`); the Stripe callback is loopback-only and nonce-validated. The relay is the sole mobile↔desktop transport (LAN link removed, PR #80).
6. **Sync trust boundary — fail-closed.** `DesktopJobSyncPolicy` drops remote job inserts/tombstones; a peer may only toggle `paused` / request run-now. Message sync is tombstone-wins (a peer's NULL can never clear a tombstone; content columns immutable).
7. **Relay transport — E2EE.** X25519 + libsodium; frames are authenticated by the pairing crypto. Revocation (`4004`) falls back to on-device. *(L2 update: the phone's relay credential is now a per-pair credential, not the shared account secret.)*
8. **At-rest secret tiers.** Desktop `secrets.p12` created `0600`, data dir `0700`; Android relay identity + KV secrets now sealed with Keystore-direct AES-256-GCM under a hardware-backed AndroidKeyStore key (L4). The PKCS#12 password derivation was hardened away from a path-reconstructable value.
9. **Backup disabled.** `allowBackup="false"` + data-extraction rules exclude all domains — conversations/memories are not eligible for cloud backup.
10. **Firebase fail-safe.** Missing `google-services.json` degrades crash/analytics to no-op rather than crashing launch; Crashlytics is gated on `FirebaseApp` being initialized.

---

## 5. Recommendations (prioritized) — all addressed

**Quick wins (low effort):**
1. **M2 / M3** — query logging + `[Relay/scan]` diagnostic gated to debug/internal; SDK logger redacted. ✅
2. **M5** — `assembleRelease`/`bundleRelease` fails fast without a real keystore. ✅
3. **L1** — per-domain HTTPS pinning for fixed hosts + a settings cleartext warning (global cleartext kept by design for LAN Ollama). ✅ (residual accepted)
4. **L6** — legacy-key deletion retried each launch + escalated warning + `deleteOnExit`. ✅

**Medium effort:**
5. **M4** — SDK moved off `mavenLocal()` to signed GitHub Packages + Gradle dependency verification with pinned SHAs. ✅
6. **L2** — per-pair credential minted post-pairing; account secret no longer in the QR. ✅
7. **L3 / L4** — `/proc` env caveat documented + non-env fallbacks; migrated off the alpha `security-crypto` to Keystore-direct AES-256-GCM. ✅

**Larger effort (was tracked as a hardening epic):**
8. **M1** — datastore encrypted at rest with SQLCipher keyed from the Keystore/keystore tier, both platforms. ✅

**Process:**
9. Re-run this audit (and consider an upstream review of the `secure-gateway` SDK) before the M7
   closed-beta/Play-Store milestone. The relay/subscription surface remains the newest and most
   security-sensitive code; the per-pair-credential change (L2) and the Keystore-direct migration (L4)
   are the most recent additions and warrant on-device confirmation (relay pair → chat/sync →
   reconnect → unpair) before beta.

---

## 6. Appendix — methodology & coverage

Reviewed: secret storage (`SecureStorage*`, `KeystorePassword`, `SecureStorageKeyStore`,
`AndroidKeystoreKeyStore`, `AesGcmEnvelope`/`KeystoreAesKey`, `CleanBreakReset`, `PosixPerms`),
credential providers (Brave/HF/Ollama), relay pairing & transport (`DesktopRelayHost`,
`RelayQrPayload`, `*RelayBytePipe*`, `RelaySubscriptionService`, `DesktopLinkServer`,
`DesktopLinkRequestHandler`, `FrameDispatcher`/`FrameMultiplexer`), subprocess execution
(`JobExecutor`, `JobService`, `DesktopJobInitializer`, `*InlineJobRunner`), archive/download integrity
(`DesktopJobLibraryStore`, `DesktopNodeProvisioner`, `DesktopModelStore`, `DesktopAuxModelStore`,
`LlamaServerBinaryStore`, `ModelDownloader`), sync (`*SyncPolicy`, `SyncController`, `LinkSyncClient`,
`upsertMessageFromPeer`), persistence drivers + encryption (`AndroidDatabaseFactory`,
`DesktopDatabaseFactory`, `DatabaseKeyProvider`), telemetry/redaction (`SafeCrashReporter`,
`ContentRedactor`, `TelemetryPayloadBuilder`), and platform/build config (`AndroidManifest.xml`,
`network_security_config.xml`, `androidApp/build.gradle.kts`, `settings.gradle.kts`,
`gradle/libs.versions.toml`, `gradle/verification-metadata.xml`).

**Not covered (out of scope / recommended follow-ups):** dynamic/runtime testing, dependency CVE
scanning (OWASP Dependency-Check / `gradle dependencyCheckAnalyze`), the internals of the
`secure-gateway` gateway service and SDK, fuzzing of the relay frame parser, and a Play pre-launch
security scan.
