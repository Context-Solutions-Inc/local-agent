# Product Requirements Document: On-Device Local Assistant with Web Search

**Document version:** 1.0
**Status:** Draft
**Last updated:** May 3, 2026

---

## 1. Overview

### 1.1 Product summary

A privacy-first, cross-platform mobile assistant that runs entirely on-device using Google's Gemma 4 model, with the ability to fetch real-time information via Brave Search API when the user's query requires up-to-date data (sports scores, market prices, news, weather, etc.). The assistant operates without sending user data to any LLM provider; only explicit web search queries leave the device, and only when the agent determines a search is needed.

### 1.2 Goals

- Deliver a responsive, capable assistant that runs without an LLM API dependency
- Provide accurate, current information for time-sensitive queries via Brave Search
- Maintain a single shared agent codebase across iOS and Android to minimize divergence and engineering cost
- Keep the user's primary conversation data on-device by default

### 1.3 Non-goals (v1)

- Voice input/output (text-only for v1)
- Multi-modal input beyond text (no image/audio understanding in v1, despite Gemma 4's capability)
- Server-side conversation sync or multi-device history
- Custom fine-tuned models or per-user model adaptation
- Tools beyond web search (no calendar, email, file access in v1)

### 1.4 Target users

Privacy-conscious users on modern smartphones who want a capable AI assistant without sending their conversations to a cloud provider, but who still expect accurate answers to questions about current events, sports, markets, and other time-sensitive topics.

---

## 2. Architecture

### 2.1 High-level structure

The application uses a layered architecture with three distinct tiers:

The native shell layer is platform-specific. iOS uses Swift with SwiftUI for the user interface, lifecycle management, and platform integration (notifications, background tasks, file system access, keychain). Android uses Kotlin with Jetpack Compose for the equivalent responsibilities. Each shell is responsible only for UI rendering, user input capture, and platform-specific concerns; it contains no business logic related to the agent or model.

The shared logic layer is implemented in Kotlin Multiplatform (KMP) and contains the agent loop, prompt construction, tool-call parsing, search result processing, caching logic, and conversation state management. This layer is consumed natively on Android and exposed via a Kotlin/Native framework on iOS. Platform-specific concerns (HTTP client, secure storage, file system) are abstracted via expect/actual declarations.

The inference layer is provided by LiteRT-LM, Google's on-device LLM runtime. A single Gemma 4 E4B model artifact (Q4 quantization) is bundled or downloaded on first launch and used identically on both platforms. LiteRT-LM is invoked from the shared KMP layer via thin platform-specific bindings (JNI on Android, C interop on iOS).

### 2.2 Data flow for a typical query

When a user sends a message, the native shell forwards it to the shared agent via a coroutine-based API. The agent runs three parallel steps before invoking Gemma 4: a pre-flight search classifier (see 3.2.1) that determines whether the query requires fresh web results, a memory retrieval step (see 3.2.4) that fetches relevant facts from previous conversations using semantic similarity, and a date/time injection step that constructs the dynamic portion of the system prompt. If pre-flight returns a high-confidence "search required" verdict, the agent executes the Brave search and includes the results in the prompt as a synthetic tool result, saving a model round-trip.

The agent then constructs the full prompt — system prompt with current date/time and retrieved memories, conversation history, the user message, and any pre-flight search results — and invokes LiteRT-LM for generation. LiteRT-LM streams tokens back; the agent inspects the stream for a function-call output. If the model emits a search tool call, the agent pauses generation, checks the local cache for an equivalent recent query, and either returns the cached result or calls Brave Search API. The result is summarized, truncated to fit the context budget, and fed back into the model as a tool result. Generation resumes until the model produces a final user-facing response. Tokens are streamed to the UI throughout, with tool-call status surfaced to the user.

After the user-facing response completes, a background memory creation step (see 3.2.4) examines the turn for memory-worthy content and stores any new facts. This step is non-blocking and never affects perceived latency.

---

## 3. Functional requirements

### 3.1 Conversation interface

The app must provide a chat-style interface where the user can type messages and receive streamed responses. Conversations are persistent across app launches and stored locally using SQLite (via SQLDelight in the shared layer). Users can start new conversations, view a list of previous conversations, and delete conversations individually or in bulk. Each conversation displays a title generated from the first user message.

Token streaming is mandatory for perceived responsiveness. The UI must render tokens as they arrive from the inference engine, not wait for completion. When a tool call is in progress, the UI displays a status indicator (e.g., "Searching the web...") in place of the streaming text, then resumes streaming when the model continues.

### 3.2 Agent loop

The shared agent loop must implement a standard ReAct-style pattern using Gemma 4's native function-calling format, with a pre-flight classification stage that can short-circuit the model's decision for queries that obviously require a web search.

#### 3.2.1 Pre-flight search injection

Before invoking the main Gemma 4 model, the agent runs the user's query through a small dedicated classification model that determines whether the query requires a web search. When the classifier returns a high-confidence "search required" verdict, the agent executes the search, injects the results into the prompt as a synthetic tool result, and Gemma 4 generates a final response in one pass — saving a full model round-trip and cutting end-to-end latency by 3–6 seconds in the common case.

The reason for using a classification model rather than rules is accuracy on the long tail of natural language. A rule-based matcher can catch "who won the Super Bowl last year" via keyword patterns, but fails on phrasings like "did the Eagles pull it off," "is Tesla up or down," "what's happening with the Fed," or "how did my team do" — all of which require search but evade simple pattern matching. **Surfacing stale or confidently wrong information about current events is a worse failure mode than the cost of running a small classifier on every query, particularly for queries about sports results, financial data, and news where the model would otherwise hallucinate plausible-sounding but incorrect answers using outdated training data.**

##### Classifier model requirements

The classifier is a small encoder-only model (target: 30–80M parameters) running in the shared KMP layer via LiteRT. Candidate base architectures include MobileBERT, DistilBERT, or a small E5/MiniLM embedding model with a classification head. The exact base model is selected during prototyping based on accuracy and inference latency on reference devices.

The classifier is fine-tuned on a labeled dataset of approximately 10,000 query/label pairs spanning: queries that require search (current sports, current markets, weather, news, recent events, prices, schedules), queries that do not require search (general knowledge, definitions, opinions, coding questions, math, settled history), and ambiguous queries (the model's confidence score is used to determine routing — see below). The dataset must be balanced across categories and include adversarial examples (e.g., "who won the 1969 Super Bowl" vs. "who won the Super Bowl last year") to teach the model to distinguish settled history from time-sensitive recency.

Inference latency must be under 50ms p95 on reference devices to keep the latency benefit of pre-flight intact. The model artifact must be under 50MB to avoid bloating the app download. The model is loaded at app start and kept resident — its memory footprint is small enough that it does not need lazy loading or unloading.

##### Confidence-based routing

The classifier outputs a probability score (0.0 to 1.0) for "requires search." The agent routes based on three thresholds:

A high-confidence threshold (default: > 0.85) triggers immediate pre-flight search before invoking Gemma 4. A low-confidence threshold (default: < 0.15) skips search and proceeds directly to Gemma 4 generation. Scores in the ambiguous middle band fall through to Gemma 4's own decision-making via the standard tool-calling flow — Gemma 4 may or may not emit a search tool call based on its own judgment. This three-way routing means the classifier never overrides Gemma 4 in ambiguous cases; it only short-circuits the obvious ones in either direction.

The thresholds are configurable via the same shipped JSON config used for other tunable parameters, allowing post-launch tuning based on observed precision and recall.

##### Query rewriting

When pre-flight fires, the user's query may need rewriting before being sent to Brave Search. "How did the markets do today" is a fine query for Brave; "did my team win" is not. Query rewriting is handled by a separate lightweight step: a small set of deterministic rules (resolve "today/yesterday/last year" to concrete dates using the injected timestamp, expand common abbreviations) followed by a fallback to Gemma 4 itself for complex rewrites. For v1, if rule-based rewriting cannot produce a confident query (e.g., "my team" with no prior context), pre-flight aborts and the request falls through to standard Gemma 4 tool-calling, where Gemma 4 can use conversation context and memory (see 3.2.4) to construct an appropriate search query.

##### Categories targeted

The classifier should learn to recognize these categories as search-required: sports results, scores, and standings; market and financial data (stocks, crypto, indices, commodities); weather and forecasts; news and current events; time-sensitive factual queries with explicit or implicit recency markers; specific scheduled events; product availability, prices, and recent reviews; person/company status updates ("is X still CEO of Y"); and any query whose answer plausibly changes within days or weeks.

The classifier should learn to recognize these as NOT search-required: definitions and explanations; settled historical facts (with explicit historical dates or contexts); opinion and reasoning questions; coding, math, and how-to questions; creative writing requests; and queries about the user's prior conversation or memory (handled by 3.2.4 instead).

##### Failure modes and safeguards

If the classifier model fails to load or returns an error, the agent must fall through to standard Gemma 4 tool-calling rather than failing the user's request. The classifier is an optimization, not a dependency.

To measure and prevent regressions, the app must support an opt-in telemetry mode (off by default, requiring explicit user consent) where classifier predictions and user thumbs up/down feedback are aggregated locally and optionally uploaded as anonymized aggregate counts (no query content) for offline classifier improvement. Without this, classifier improvement post-launch relies on synthetic dataset expansion and external evaluation.

#### 3.2.2 Function-calling tool

The agent supports a single tool in v1: `web_search`. The function schema is defined as:

```json
{
  "name": "web_search",
  "description": "Search the web for current information. Use this for questions about recent events, current scores, market prices, weather, or any topic where information may have changed recently.",
  "parameters": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "The search query. Should be concise and specific."
      }
    },
    "required": ["query"]
  }
}
```

The agent must enforce a maximum of three tool calls per user turn to prevent runaway loops. If the model attempts a fourth tool call, the agent injects a system message instructing it to answer with available information and terminates the tool-call phase.

#### 3.2.3 System prompt and temporal awareness

The system prompt is constructed dynamically at the start of each user turn (not at conversation start) and must include the current date and time. This is critical: Gemma 4's training data has a fixed knowledge cutoff, and without an injected timestamp the model cannot reason correctly about queries containing relative temporal expressions like "yesterday," "last year," "this morning," or "next week." The injected timestamp also enables the model to interpret search results correctly (a result dated three days ago means something different on different days) and to flag stale information back to the user.

The timestamp must include: the full date in ISO 8601 format (YYYY-MM-DD), the day of the week, the current local time with timezone offset, and the timezone name. Example injection block within the system prompt:

```
Current date and time: 2026-05-03 (Sunday), 14:32 EDT (America/Toronto, UTC-04:00).
Use this timestamp to interpret relative time expressions in user queries
(e.g., "yesterday," "last week," "this morning") and to assess the freshness
of any search results you receive.
```

The timezone is determined from the device's current locale settings, not the user's account or a stored preference, so travel and DST transitions are handled correctly. The timestamp is regenerated on every user turn rather than cached, since conversations may span hours or days.

The system prompt must additionally instruct the model on tool usage policy (when to search vs. when to answer from training data), citation behavior (always reference source URLs from search results in its response), uncertainty handling (acknowledge when it doesn't know rather than confabulate), and conciseness guidelines. The system prompt should bias toward not searching for general knowledge questions (definitions, historical facts, explanations) and toward searching for time-sensitive queries — though in practice, the pre-flight injection layer (3.2.1) handles the most obvious time-sensitive cases before the model is even invoked.

When pre-flight injection has fired and search results are already in the prompt, the system prompt must include an additional instruction reminding the model that the search has already been performed on its behalf and that it should answer from the provided results rather than emit a redundant tool call.

#### 3.2.4 Agent memory

The agent must maintain a persistent memory store that allows the assistant to recall relevant facts about the user across conversations. Without memory, the assistant feels stateless and forgets context that should obviously persist (the user's name, their favorite team, their work, their preferences, recurring topics they care about). This is a key differentiator for an on-device assistant: because data never leaves the device, persistent memory carries no privacy cost and there is no reason not to offer it.

##### Memory store

Memories are stored in a local SQLite table with the following structure: a unique ID, the memory text (a natural-language fact, e.g., "User's favorite NFL team is the Eagles"), a category tag (e.g., personal, preference, professional, interest), the conversation ID where the memory was created, a creation timestamp, a last-accessed timestamp, an embedding vector for semantic retrieval, and an optional expiration date for time-bound facts ("user is traveling to Tokyo next week").

Each memory is stored alongside a 384-dimensional embedding generated by a small sentence-embedding model (target: an INT8-quantized version of all-MiniLM-L6-v2 or equivalent, approximately 25MB). Embeddings enable semantic retrieval — finding memories relevant to the current query even when there's no exact keyword overlap.

The memory store is bounded at 1,000 entries by default (user-configurable). When the cap is reached, eviction prioritizes: expired time-bound memories first, then memories not accessed in over 90 days, then lowest-relevance memories as scored by an LRU + access-frequency heuristic.

##### Memory creation

Memory creation runs as a background step after each completed user turn. A small classification model — sharing the same architecture and base as the pre-flight classifier (3.2.1) but with a different fine-tuned head — examines each user turn and assistant response and decides whether the exchange contains memory-worthy facts. The classifier is trained to recognize: explicit statements of preference, identity, or context ("I'm a software engineer," "I live in Toronto," "my favorite team is the Eagles"), recurring topics the user engages with (sports teams, stocks they follow, projects they're working on), and stable facts about the user's life (family members, pets, professional context).

The classifier must NOT extract: transient queries ("what's the weather today" — the question is not memory-worthy even though the location might be), private information the user did not explicitly volunteer, ephemeral context (one-off questions), or anything the user has explicitly asked not to be remembered.

When the classifier identifies memory-worthy content, it produces a candidate memory text. For v1, candidate memories are extracted via a templated approach using the classifier's output and span markers; in v1.x, Gemma 4 itself can be used to generate well-formed memory text via a brief background inference call. Before storage, candidate memories are deduplicated against existing memories using embedding similarity (cosine similarity > 0.85 means the memory already exists or supersedes an older one).

Memory creation is non-blocking — it runs after the user has received their response, on a background thread, and a failure to create a memory must never affect the user-facing experience.

##### Memory retrieval

At the start of each user turn, the agent runs a memory retrieval step in parallel with pre-flight classification. The user's query is embedded using the same sentence-embedding model, and the top-K most similar memories are retrieved via cosine similarity (default K=5, threshold 0.5). Retrieved memories are filtered for expiration and recency relevance, then injected into the system prompt under a dedicated section:

```
Relevant context from previous conversations:
- User's favorite NFL team is the Philadelphia Eagles.
- User lives in Toronto, Ontario.
- User is a software engineer working on mobile apps.
```

Memory retrieval latency must be under 100ms p95 on reference devices. Embedding generation is the dominant cost; the embedding model is loaded at app start and kept resident.

The retrieval threshold (0.5) is intentionally permissive on the recall side. False positives — irrelevant memories injected into the prompt — are a minor cost (slight context bloat, possible model confusion). False negatives — failing to retrieve a relevant memory — are a worse failure because the user notices when the assistant forgets something it should remember. Retrieved memories also influence pre-flight: a query like "did my team win" is meaningless without memory, but with the Eagles memory retrieved, query rewriting can produce "did the Philadelphia Eagles win" and pre-flight can fire normally.

##### User control and transparency

Memory must be a first-class user-facing feature, not a hidden implementation detail. The settings screen must include a "Memory" section showing all stored memories in a list, with the ability to delete individual memories, edit memory text, clear all memories, and disable memory creation entirely (existing memories remain unless deleted). Each conversation in the conversation list must indicate when memories were created during that conversation, and users can tap to see what was remembered.

Users must be able to explicitly create memories via in-conversation commands (e.g., "remember that I prefer dark roast coffee") and explicitly forget memories ("forget what I said about my job"). These commands are detected by the same classifier (with explicit-instruction labels) and given priority over automatic extraction.

##### Failure modes and safeguards

If the embedding model or memory classifier fails to load, the agent must fall through to operating without memory rather than failing the user's request. As with the search classifier, memory is an enhancement layer that must degrade gracefully.

Memory must never be uploaded, synced, or transmitted off-device in v1. Even opt-in telemetry (3.2.1) must explicitly exclude memory content. This is a hard constraint: the privacy story for an on-device assistant breaks if intimate user facts can leak.

### 3.3 Web search via Brave API

Web searches are made via the Brave Search API using the standard endpoint. The API key must be stored securely (iOS Keychain, Android EncryptedSharedPreferences) and never logged. Each search request must include a 10-second timeout. Failures (network error, rate limit, invalid response) must be returned to the model as a tool result containing an error message rather than crashing the agent loop, allowing the model to either retry with a different query or inform the user.

Search results are post-processed before being fed back to the model. The agent extracts the top five organic results, retains only title, URL, and snippet (truncated to 200 characters per snippet), and formats them as a compact JSON array. Raw HTML, ads, and supplementary widgets are discarded. The total tool result payload must not exceed 4KB to preserve context budget.

### 3.4 Local search cache

A local SQLite cache stores recent search results keyed by normalized query string (lowercased, whitespace-collapsed). Cache entries include the original query, the formatted result payload, and a timestamp. Cache entries expire based on query category:

Time-sensitive queries (containing terms like "now," "today," "current," "score," "price," "weather") expire after 5 minutes. General queries expire after 24 hours. The cache is bounded at 500 entries with LRU eviction. Users can clear the cache from settings. Cache hits are surfaced in the UI with a subtle indicator so users understand when results are not freshly fetched.

### 3.5 Model management

The Gemma 4 E4B Q4 model artifact (approximately 2.5–3 GB) must be downloaded on first launch rather than bundled with the app to keep initial install size manageable. The download UI must show progress, support pause/resume, and verify the model checksum before activation. Downloads use the platform's background download facilities (URLSession background sessions on iOS, WorkManager on Android) to survive app suspension.

If the device has insufficient storage, the app must surface this clearly and refuse to download. If the device is detected as being on a metered connection, the app must require explicit user confirmation before downloading.

### 3.6 Settings

Users can configure: their Brave API key (required to enable search), the model variant if multiple are supported in the future, cache duration overrides, conversation history retention, and a master "search enabled" toggle that disables the search tool entirely (for fully offline use).

---

## 4. Non-functional requirements

### 4.1 Performance

First token latency for a typical query (no tool call) must be under 2 seconds on reference devices (iPhone 15 Pro, Pixel 9 Pro). Sustained token generation must exceed 15 tokens/second on the same devices. End-to-end latency for a query requiring a single web search must be under 12 seconds in 90% of cases on a good network connection.

The app must remain responsive during inference; UI input must never block on model output. Inference runs on a dedicated background thread with cancellation support so users can interrupt generation.

### 4.2 Memory

The app's resident memory footprint, including the loaded Gemma 4 model, must stay under 4 GB on iOS and Android. Gemma 4 is loaded lazily on first query, not at app launch. The model can be unloaded after a configurable idle period (default 5 minutes) to return memory to the system. Reloading takes 2–4 seconds and is acceptable.

The smaller auxiliary models (pre-flight search classifier, memory extraction classifier, sentence embedding model) are loaded at app start and kept resident throughout the app lifecycle. Their combined footprint must stay under 200MB. These models are small enough that the cost of repeated load/unload cycles outweighs the memory savings.

The KV cache must be sized to support at least 8K tokens of context (system prompt with injected memories + conversation history + current turn + tool results). Conversation history is truncated using a sliding window strategy when the budget is exceeded; the system prompt and retrieved memories are always preserved.

### 4.3 Battery and thermal

Sustained generation should not raise device temperature beyond the OS's "fair" thermal state under normal use. The app must monitor thermal state on both platforms and throttle generation (reducing tokens/second) if the device approaches "serious" thermal state. Users must be warned if they attempt to use the assistant while the device is in "critical" thermal state.

### 4.4 Privacy and security

User conversations and memories never leave the device. The only network traffic the app makes is to the Brave Search API, and only when the agent invokes the search tool. No analytics, telemetry, or crash reporting includes conversation or memory content. If crash reporting is included, it must be opt-in and must scrub all message and memory content. The opt-in classifier-improvement telemetry described in 3.2.1 must explicitly exclude all memory content and all conversation text — only aggregate prediction counts and user feedback signals may be transmitted.

The Brave API key is stored only in the platform's secure storage (Keychain / EncryptedSharedPreferences). Search queries themselves are sent to Brave by definition, but the app must not include any user identifier, device identifier, or conversation context in those requests beyond the query string itself. Notably, retrieved memories must not be appended to search queries even when they would improve query specificity — query rewriting that uses memory context (e.g., resolving "my team" to "Eagles") is acceptable, but the resulting search query is the only thing sent to Brave.

Memory storage on disk must be protected by the platform's standard data-at-rest encryption (Data Protection on iOS, file-based encryption on Android). On iOS, the database file must use `NSFileProtectionCompleteUntilFirstUserAuthentication` at minimum.

### 4.5 Offline behavior

The assistant must remain functional offline for any query that does not require web search. If the model emits a search tool call while offline, the agent returns a tool result indicating no network is available, and the model is instructed to answer with available information and inform the user that the answer may be out of date.

### 4.6 Compatibility

iOS: minimum supported version is iOS 17. The app requires a device with at least 8 GB of RAM (iPhone 15 Pro and later, iPad with M1 or later). Devices below this threshold will show a compatibility message at install or first launch.

Android: minimum supported version is Android 13 (API 33). The app requires at least 8 GB of RAM and ARM64 architecture. Devices below this threshold will show a compatibility message.

---

## 5. Technical specifications

### 5.1 Project structure

```
/shared              # Kotlin Multiplatform module
  /commonMain        # Agent loop, prompt construction, cache, schemas
  /androidMain       # JNI bindings to LiteRT-LM, OkHttp client
  /iosMain           # C interop to LiteRT-LM, NSURLSession client
/androidApp          # Kotlin + Compose native shell
/iosApp              # Swift + SwiftUI native shell
/models              # (gitignored) Local model artifacts for development
```

### 5.2 Key dependencies

Shared module uses Ktor for HTTP, kotlinx.serialization for JSON, kotlinx.coroutines for async, SQLDelight for storage, and platform-specific LiteRT-LM bindings. Android app uses Compose, Hilt for DI, and standard AndroidX libraries. iOS app uses SwiftUI and Swift Concurrency; the shared KMP framework is consumed via SPM.

### 5.3 Inference integration

LiteRT-LM is integrated via its native libraries on each platform. On Android, the AAR is included as a Gradle dependency and accessed via JNI. On iOS, the xcframework is integrated via SPM and accessed via C interop from Kotlin/Native. The shared layer exposes a unified `InferenceEngine` interface with methods for loading a model, running generation with streaming, and parsing function-call outputs from the token stream.

### 5.4 Function-call parsing

Gemma 4 emits function calls in a structured format defined by Google. The agent parses the model's output stream incrementally, detecting the function-call delimiter and accumulating the JSON payload until complete. Once a complete function call is detected, generation is paused, the tool is executed, and the result is appended to the prompt for continued generation. The parser must be robust to partial JSON during streaming and must handle malformed output by returning a parse-error tool result rather than crashing.

---

## 6. User experience requirements

### 6.1 First-run experience

On first launch, the user is shown a brief onboarding flow explaining that the assistant runs on-device, that web search requires a Brave API key (with a link to obtain one), and that the model needs to download before use. The onboarding flow gates the model download behind explicit user consent and surfaces the download size and estimated time.

### 6.2 Empty states and errors

The app must handle gracefully: no Brave API key configured (search tool disabled, banner prompts user to add key), no network connection (search tool returns offline error, model proceeds without search), model not downloaded (chat is disabled, user is directed to download flow), insufficient storage during download (download fails with actionable error message), and Brave API errors (rate limit, invalid key, server error — all surfaced as conversational tool errors rather than blocking dialogs).

### 6.3 Transparency

When a search occurs, the UI must display the search query and the source URLs of results used. Users should be able to tap a source to open it in a browser. This is both a trust feature and a hedge against model hallucination — users can verify the underlying source.

---

## 7. Success metrics

For v1 launch, we will measure: time to first token (target: p50 under 1.5s, p90 under 2.5s), search-tool invocation rate (target: 20–40% of queries), pre-flight classifier precision (target: 95%+ on the high-confidence band — false positives waste API calls but more critically, missed search opportunities lead to confidently wrong answers about current events, which is the worst failure mode), pre-flight classifier recall on a held-out evaluation set of time-sensitive queries (target: 90%+), pre-flight hit rate (target: 70%+ of all queries that ultimately invoke search should be caught by pre-flight rather than requiring a Gemma 4 round-trip), memory retrieval relevance (measured via offline evaluation on labeled memory/query pairs, target: 80%+ of retrieved memories rated relevant by labelers), memory creation precision (target: 90%+ of auto-created memories rated useful by users in the memory review screen), search result usefulness (measured via user thumbs up/down on responses that used search), conversation completion rate (% of conversations where the user sends more than one message), and crash-free session rate (target: >99.5%).

A standing offline evaluation set must be maintained for the pre-flight classifier covering: known time-sensitive query templates ("who won [event]," "what's [stock] at," etc.) with date-shifted variants to catch seasonal drift, adversarial pairs that distinguish current events from settled history, and naturalistic phrasings that evade simple keyword matching. Regression on this evaluation set blocks classifier model updates from shipping.

---

## 8. Open questions and risks

### 8.1 Open questions

The exact LiteRT-LM API surface is still evolving; integration details may need adjustment as the framework matures. The function-call format produced by Gemma 4 is documented but the parsing edge cases under streaming are not yet fully characterized — early prototype work is required to validate. Whether to support multiple model sizes (E2B for older devices, E4B for newer) is deferred to v1.1 pending performance data on a wider device range. The pre-flight classifier and memory extraction classifier may share a base model with separate task heads or be entirely separate models — this is a prototyping decision driven by accuracy and footprint trade-offs. The size and source of the labeled training datasets for both classifiers is not yet finalized; an initial dataset of 10,000 examples per classifier is the working assumption but may need expansion.

### 8.2 Risks

The primary technical risk is performance on mid-tier devices. E4B at Q4 may not meet the 15 tokens/second target on devices below the reference set, and the additional auxiliary models add memory pressure that may not be acceptable on 8GB devices. Mitigation: maintain E2B as a fallback for Gemma 4, and benchmark the full model stack (Gemma + classifiers + embedding model) across a broader device matrix before launch.

The most user-visible risk is the pre-flight classifier producing confidently wrong answers when it misses a time-sensitive query. A query like "did the Eagles win the Super Bowl last year" that fails classification would be answered from Gemma 4's training data, potentially producing a confident but incorrect answer. Mitigation: bias the classifier toward high recall in the time-sensitive direction; pair the classifier with a complementary "uncertainty injection" in the system prompt instructing Gemma 4 to express low confidence and recommend verification when answering questions that depend on recent events; and maintain the offline evaluation set described in section 7 as a regression gate.

A secondary product risk is memory quality — the assistant extracting incorrect, embarrassing, or unwanted memories. Mitigation: classifier precision target of 90%+, easy in-app review and deletion of memories, opt-out of memory entirely, and an explicit "forget" command. Memories are surfaced transparently in the UI so users can spot mistakes.

The primary business risk is Brave API cost. If usage scales beyond the free tier, per-query costs become a recurring expense. Mitigation: aggressive caching (already specified), per-user rate limiting, and a clear plan for migrating heavy users to bring-your-own-key.

---

## 9. Out of scope (deferred to future versions)

Multi-modal input (image and audio understanding via Gemma 4's native capabilities), additional tools (calendar, email, local file search, calculator), conversation export, server-sync of conversations across devices, custom system prompt configuration, voice input/output, widget and Siri/Assistant integration, and tablet-optimized layouts.
