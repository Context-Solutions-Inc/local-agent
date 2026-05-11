# Google Play Data Safety Notes

**Purpose:** internal cheat-sheet for completing the Play Console Data
Safety form. Phase G owner: lawrence.ley@contextsolutions.ca.

The Play Data Safety form asks (1) what data the app collects, (2) what
data the app shares with third parties, (3) security practices. Use this
document as the source of truth when filling each section; everything
below is enforced in code (see `docs/PRIVACY_POLICY.md` for the user-facing
version + code references).

---

## Section 1 — Data collection

**Does the app collect or share any of the required user data types?** Yes
(narrow set — see below). The default answer for most categories is
**No** because nothing is collected by default.

### App activity

| Data type | Collected? | Shared? | Purpose | Optional? |
|---|---|---|---|---|
| App interactions | **Yes (opt-in)** | Yes — Firebase Analytics | App functionality, Analytics | Yes |
| In-app search history | **No** | No | — | — |
| Other user-generated content | **No** | No | — | — |

**App interactions detail:** if the user opts in to "Share anonymous
counters" (default OFF), the app sends aggregate daily counters via
Firebase Analytics — query count, search invocation count, memory
operation counts, latency percentiles (p50/p95/p99) for inference,
search, retrieval, and pre-flight. No content; counter values only.

### App info and performance

| Data type | Collected? | Shared? | Purpose | Optional? |
|---|---|---|---|---|
| Crash logs | **Phase D (opt-in)** | Yes — Firebase Crashlytics | Crash diagnostics | Yes |
| Diagnostics | Phase D as above | Same | Same | Yes |
| Other app performance data | Same | Same | Same | Yes |

**Crash logs detail (Phase D):** Firebase Crashlytics receives crash
reports with our `ContentRedactor` filter applied at the SDK egress
point. The filter strips Brave API keys, search queries, message content,
and memory text from exception messages and stack-trace string args
before egress. Stack-trace structure (class names, line numbers, native
addresses) is retained.

### Personal info / Financial / Health / Messages / Photos / Audio / Files / Calendar / Contacts / Location / Web browsing / Device or other IDs

**None collected, none shared.** The app stores user-supplied text
(conversation, memories, Brave API key) on-device only and never
transmits it to any analytics or first-party server.

---

## Section 2 — Data sharing with third parties

| Recipient | Data shared | When | User-controllable? |
|---|---|---|---|
| Brave Search (`api.search.brave.com`) | Web search query text | When the assistant invokes the web_search tool | Yes — search-disable toggle in Settings, or omit BYOK key |
| Firebase Analytics (Google) | Aggregate counters (no content) | Once per 24 h, only when opt-in toggle is ON | Yes — telemetry toggle |
| Firebase Crashlytics (Google) | Redacted crash reports | At crash time, only when opt-in toggle is ON | Yes — Phase D adds the toggle |

**No advertising network is used.** No data is shared for advertising or
marketing.

---

## Section 3 — Security practices

| Practice | Status |
|---|---|
| Data encrypted in transit | **Yes** — HTTPS to Brave + Firebase only |
| Data encrypted at rest | **Yes** — Android File-Based Encryption Credential-Encrypted Storage (default for `Context.dataDir`); Brave key in EncryptedSharedPreferences |
| User can request data deletion | **Yes** — Settings → Memory → "Clear all"; Android Settings → App → Clear storage |
| Independent security review | Not yet (M6 internal audit only) |
| Complies with Families Policy | N/A — app is not targeted at children |

---

## Section 4 — Specific data-type answers (canonical phrasing for the form)

When the Play Data Safety form asks specifically about each row, use the
phrasings below to keep answers consistent with the Privacy Policy.

### Q: "Does your app collect or share any user data?"

**A:** Yes. The app collects aggregate usage counters and crash reports
**only when the user opts in.** When opted in, this data is transmitted
to Firebase Analytics (counters) and Firebase Crashlytics (crash
reports). The app also forwards user-typed search queries to the Brave
Search API when the user invokes web search, but only the query text is
shared — no other user data accompanies it.

### Q: "Is data collection optional?"

**A:** Yes, for both telemetry counters and crash reports. Search
queries are sent only when the user triggers a web search; the
web-search feature can be disabled entirely via the Settings toggle.

### Q: "Why is each data type collected?"

- **App interactions (counters):** Analytics — understand which features
  users use, identify pain points (e.g., classifier middle-band routing),
  and prioritize improvements.
- **Crash logs:** App diagnostics — fix bugs the user encounters.
- **Search query text (forwarded to Brave):** App functionality — the
  search query is what the user wants the web to answer.

### Q: "Is this data shared with other companies or organizations?"

**A:** Yes:

- Search queries are sent to Brave Search (Brave Software, Inc.).
- Aggregate counters are sent to Firebase Analytics (Google LLC).
- Crash reports (Phase D) are sent to Firebase Crashlytics (Google LLC).

The app does not sell user data and does not share data with any other
third party.

### Q: "Are users notified before data is collected?"

**A:** Yes. The first-run onboarding flow (Phase E) includes an explicit
opt-in screen for anonymous telemetry; the default is OFF. The Settings
surface in-app explains what is and isn't collected and lets the user
toggle the consent at any time.

---

## Open items before Phase G submission

- [ ] Confirm Crashlytics opt-in UX after Phase D ships.
- [ ] Replace "Effective: TBD" in PRIVACY_POLICY.md with launch date.
- [ ] Host PRIVACY_POLICY.md at a public URL (Play Store requires a link).
- [ ] Add the privacy policy URL to the Play Console listing.
- [ ] Re-confirm the answers above with the post-Phase-D code state.

---

**Authority:** This document is the internal reference. The user-facing
authoritative document is `docs/PRIVACY_POLICY.md`. Discrepancies are
bugs; the Privacy Policy wins.
