# Privacy Policy

**Effective:** TBD (Phase G launch date)
**Last updated:** 2026-05-11 (Phase C draft)
**App:** Local Agent (`com.contextsolutions.localagent`)

---

## Plain-language summary

The Local Agent assistant runs entirely on your device. Your conversations,
the things it remembers about you, and the web searches you make never leave
your device — with two narrowly scoped exceptions:

1. **Web search**, which sends your search query to the Brave Search API
   when the assistant decides a web search is needed (and only the search
   query — no surrounding conversation, no memories).
2. **Anonymous usage counters** (off by default), which transmit aggregate
   numeric counts once per day to help us improve the app — never the text
   of your queries, memories, or replies.

This policy is the formal version of those two sentences. The technical
details below match the source code in this repository — the constraints
are enforced in code, not just on paper.

---

## What data the app handles

### 1. Conversations and memories — **on device only**

Your chat conversations with the assistant, and the persistent memories the
app extracts from those conversations ("User's favorite team is the
Philadelphia Eagles", etc.), are stored in a local SQLite database in your
app's private storage area. They are:

- Protected by your device's standard at-rest encryption (Android File-Based
  Encryption Credential-Encrypted Storage on Android 13+).
- Never transmitted to any server we operate, never shared with any third
  party, never used to train models.
- Removable individually (per-memory delete + clear-all) or wholesale (clear
  app storage from Android Settings → Apps).

### 2. Brave API key — **stored locally, transmitted to Brave only**

If you provide a Brave Search API key (BYOK), it's stored in
EncryptedSharedPreferences on your device. It's sent to Brave Search with
each web search request you trigger (this is what authenticates you to the
Brave API; without it, no search). It's not transmitted anywhere else.

### 3. Web search queries — **sent to Brave only**

When the assistant makes a web search on your behalf, it sends the search
query to `api.search.brave.com`. The query is the actual text being
searched (e.g., "philadelphia eagles game result"). The app does NOT send:

- Your other messages in the conversation.
- Any retrieved memories from previous conversations (even when memory
  context is used to *form* the query, e.g., resolving "did my team win"
  → "philadelphia eagles game result").
- Any device identifier or stable per-user identifier.

Brave's own privacy practices apply to data they receive — see
<https://search.brave.com/help/privacy-policy>.

### 4. Opt-in anonymous telemetry — **off by default**

If you enable "Share anonymous counters" in Settings or during onboarding
(default: OFF), the app sends a small set of aggregate daily counters to
our analytics backend (Firebase Analytics). The counters report:

- **What you did**, by category: how many queries you ran today; how many
  triggered a web search; how many memories were created, used, or
  forgotten.
- **How fast it was**: latency percentiles (p50/p95/p99) for first-token
  generation, search, memory retrieval, and pre-flight classification.

The counters do NOT carry:

- The text of any query you typed.
- The text of any memory the app stored.
- The text of any assistant response.
- The text of any web search query.
- A stable per-user identifier or per-device fingerprint beyond a coarse
  `platform=android` / `device=pixel7` tag.

You can toggle telemetry off at any time in Settings. Once off, the app
stops sending data on the next upload cycle (within 24 hours). The
already-on-device counter rows stay in your local database until you clear
app storage.

### 5. Crash reports — **opt-in, content-scrubbed (Phase D)**

When Phase D lands, the app will offer Firebase Crashlytics for crash
reporting. Crashlytics is opt-in (separate from the telemetry toggle).
Every crash report passes through a content-redaction filter before it
leaves the device — the filter strips Brave API keys, search query
strings, message content, and memory text from exception messages and
stack-trace strings. Crashlytics still reports the crash signature
(class + line number + native stack) which is necessary for diagnosing
crashes.

---

## What we explicitly DO NOT do

- We do not use your conversations to train any model.
- We do not sell your data.
- We do not share your data with any third party other than Brave Search
  (for the search queries you explicitly trigger) and Firebase Analytics
  (for the counters, if you opt in).
- We do not transmit your memories anywhere.
- We do not transmit your conversation history anywhere.
- We do not collect a stable per-user identifier.
- We do not track you across apps or websites.

---

## Children

The app is not directed to children under 13.

---

## Changes to this policy

Material changes will be communicated in-app on next launch and via a
revised version of this document.

---

## Contact

Context Solutions Inc. — `info@contextsolutions.ca`

---

*This policy is enforced in code:*

- *Counter-only telemetry contract:* `:shared/.../telemetry/AnalyticsSink.kt`
  + `TelemetryPayloadBuilder.kt`. The `TelemetryPayloadBuilderTest`
  asserts that an output payload never contains text from the `memories`
  or `messages` SQL tables.
- *On-device storage scope:* `:shared/.../sqldelight/.../Memories.sq` +
  `Memories.kt`. The `MemoryRetriever`/`MemoryExtractor` code paths emit
  log lines that contain counts and IDs only, never memory text (M5
  WS-12 audit + comment markers).
- *Brave egress scrubbing:* `:shared/.../platform/HttpEngineFactory.android.kt`
  redacts the `Authorization` / `X-Subscription-Token` header in OkHttp
  logs.
