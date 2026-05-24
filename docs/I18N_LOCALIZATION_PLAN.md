# Multi-language support via a runtime string catalog

> **Status:** Planning only — no feature code written yet. This document is the
> resumable spec. See the Delivery workflow section for how implementation should
> be branched, PR'd, and gated on device validation.

## Context

The app is being prepared for multi-language support. Today **every user-visible
string is hardcoded** — ~800 literals across ~12 Compose screens, plus English
literals in the `:shared` deterministic formatters, voice-command/ack phrases, and
the LLM system-prompt blocks. The one piece that already exists is the
**response-language** layer: `LanguagePreferences` + `PreferredLanguage` (10
languages, ISO codes, `preferredLanguageFlow()`, default EN) drives the
system-prompt "Respond in X" directive and a per-language Unicode `ResponseFilter`.

**Goal:** make the *displayed and spoken* strings configurable by locale so a new
language can be added as a **pure data file with no recompile** (bundled JSON now,
optionally remote-downloaded later). One setting — the existing `PreferredLanguage`
— drives UI chrome, deterministic chat cards, voice/ack/TTS phrases, AND the LLM
system-prompt instruction blocks. English is the bundled default and the fallback
for any missing key. We ship/validate English first; adding e.g. Spanish becomes a
matter of dropping in `strings_es.json`.

**Decided scope:** UI chrome + deterministic cards + voice/ack/TTS + LLM prompt
blocks. **Deferred (tracked separately):** localizing `locations.json` /
`search_defaults.json` display names, and the `search_cache` locale-keying bug
(same query across countries can serve stale results — see Deferred section).

## Approach

A unified **runtime `StringCatalog`** in `:shared/commonMain`, keyed by stable
string IDs, NOT Android `res/values-xx/strings.xml`. The catalog loads JSON
"packs" (one per language), overlays the selected-language pack on top of the
always-present EN fallback pack, and exposes the active pack reactively, derived
from `LanguagePreferences.preferredLanguageFlow()`. Both Compose UI and the
`:shared` consumers read through it.

This rides existing patterns: assets are already loaded in Hilt `@Provides` methods
(`ClassifierModule.providePreflightConfig` reads `context.assets.open(...)`, parses
with kotlinx-serialization, provides a `@Singleton` with a try/catch fallback), and
the composition root already does `collectAsState()` of a Hilt VM flow (theme mode).

### Core types — `shared/src/commonMain/.../i18n/`

- **`StringPack`** — immutable parsed JSON for one language + its resolved plural
  rule. Pure data; `StringPack.parse(json, code)` in commonMain (reuses the
  existing `Json { ignoreUnknownKeys = true }`).
- **`Strings`** — the accessor consumers call. Wraps an active pack + the EN
  fallback pack; missing keys resolve against EN.
  - `get(key, vararg args): String`
  - `plural(key, count, vararg args): String`
  - `list(key): List<String>`
- **`StringCatalog`** (interface; `DefaultStringCatalog` impl):
  - `val active: StateFlow<Strings>` — drives Compose + non-Compose reads.
  - `fun stringsFor(language): Strings` — synchronous, for per-turn agent use.
  - `active` is built from `preferredLanguageFlow().map { Strings(loadOrEn(it), enPack) }
    .stateIn(scope, Eagerly, Strings(enPack, enPack))` — seeds at EN, flips when a
    non-EN pack resolves (loader is `suspend`, never blocks the first frame).
- **`PluralRules`** — hand-written commonMain `when` over language code; the 10
  shipped languages collapse to 4 rule functions: `other`-only (zh/ja/ko),
  one/other (en/de/es/it/pt), French (0..1 → one), Russian one/few/many/other.
  No ICU dependency. `other` is mandatory and the universal fallback.
- **Positional formatter** — hand-rolled `%<n>$[sd]` substitutor (do NOT use
  `String.format` positional specifiers — JVM-only; `:shared` has an `iosMain`
  set). Handle `%%` → `%`.
- **`StringKeys`** — central `object` of `const val` key IDs. All accessors use
  `StringKeys.X`, never raw strings — gives find-usages, rename safety, and the
  guardrail test (below).

### KMP asset seam — injected loader, NOT expect/actual

commonMain `fun interface StringPackLoader { suspend fun load(code: String): String? }`.
Android impl (in the Hilt module) does `context.assets.open("i18n/strings_$code.json")`
on `Dispatchers.IO`, returns null on `FileNotFoundException`. Chosen over
expect/actual because `:shared` has an `iosMain` set that would otherwise need a
stub actual, and this matches the existing asset-in-Hilt convention. Parsing stays
in commonMain (loader returns a `String`). Future remote packs: loader checks
`filesDir` before `assets` — one-line change, seam already supports it.

**Packs live at** `androidApp/src/main/assets/i18n/strings_en.json`, `strings_es.json`, …
Only EN ships required; if EN fails to parse, **throw at startup** (it's the fallback floor).

### JSON pack schema

One object per pack; value shape distinguishes simple / plural / list:

```json
{
  "_meta": { "code": "en", "plurals": "english" },
  "settings.title": "Settings",
  "weather.header": "Weather for %1$s",
  "clock.alarms.count": { "one": "You have one alarm set: %1$s.", "other": "You have %1$d alarms set:" },
  "memory.count": { "one": "%1$d memory", "other": "%1$d memories" },
  "voice.cmd.send": ["send", "send it", "send message", "submit"],
  "ack.working": ["Got it. Working on your response.", "On it. Give me a moment."],
  "prompt.base_template": "You are a helpful, accurate, and privacy-respecting AI assistant…"
}
```

### Accessor APIs

- **commonMain consumers** (formatters, `PromptAssembler`, `AgentLoop`):
  constructor-inject a resolved `Strings`. `AgentLoopFactory` already threads
  `responseLanguage` per turn → it calls `catalog.stringsFor(language)` once per
  turn and passes the `Strings` down (so a Settings flip takes effect next turn,
  exactly like the current language threading). Resolve once per turn, never
  per-token.
- **Compose**: `val LocalStrings = staticCompositionLocalOf<Strings> { error(...) }`,
  seeded in `MainActivity.setContent` from `catalog.active.collectAsState()`
  (mirrors the existing theme-mode pattern). `staticCompositionLocalOf` is correct
  here — the reference only changes on a deliberate language flip, so a full-tree
  re-provide is fine and rare. Accessors: `@Composable @ReadOnlyComposable fun
  tr(key, vararg args)`, `trPlural`, `trList`.
- **Non-Composable Android** (notification builders, ViewModels): read
  `catalog.active.value` snapshot, or inject `StringCatalog`.

### Voice command + ack rewiring

- `AckPhrasePicker` already takes `phrases: List<String>`; its provider passes
  `catalog.active.value.list("ack.working")` / `"ack.still_working"`. Logic
  untouched.
- `VoiceCommand.match(spoken, strings)` — refactor the static phrase→command map
  to build from `strings.list("voice.cmd.*")`. `normalize()` unchanged. Caller
  (dictation callback) passes the active `Strings`. **Note:** voice triggers are a
  *recognition* surface — per-language phrase lists must be authored against what
  the recognizer actually emits (QA concern, not free).

## Files to create / modify

**New (`shared/src/commonMain/.../i18n/`):** `StringPack.kt`, `Strings.kt`,
`StringCatalog.kt` (+ `DefaultStringCatalog`), `PluralRules.kt`, positional
formatter, `StringPackLoader.kt`, `StringKeys.kt`.

**New (`androidApp`):** `app/di/I18nModule.kt` (provides EN pack + catalog
`@Singleton` + Android `StringPackLoader`, mirroring `ClassifierModule`);
`app/ui/i18n/LocalStrings.kt` (+ `tr`/`trPlural`/`trList`);
`src/main/assets/i18n/strings_en.json` (grows per migration phase).

**Modify (`:shared`):** `agent/WeatherResponseFormatter.kt`,
`StockResponseFormatter.kt`, `ClockResponseFormatter.kt` — **convert `object` →
`class`** taking `Strings` (the big mechanical friction point; `TodoResponseFormatter`
is already a class — use as template); `TodoResponseFormatter.kt`;
`PromptAssembler.kt` (const blocks → keys; `languageDirective` already
parameterized — ensure not double-emitted once base template is localizable);
`AgentLoop.kt` (`FRIENDLY_ENGINE_ERROR`, `WEATHER_LOCATION_PROMPT_TEXT`,
`CLOCK_GUIDANCE_TEXT`, `TODO_GUIDANCE_TEXT`, inline memory ack → keys).

**Modify (`:androidApp`):** `app/di/AgentModule.kt` (formatter/PromptAssembler/
AgentLoopFactory wiring for injected `Strings`); `MainActivity.kt` (seed
`LocalStrings`); `app/ui/chat/VoiceCommand.kt`, `AckPhrasePicker.kt`; the ~12 UI
screens under `app/ui/` (`chat/ChatScreen.kt` is the largest, then
`settings/SettingsScreen.kt`, the 5 `onboarding/` screens, `memory/`, `history/`,
`clock/`, `todo/`, `download/`, `settings/SearchSourcesScreen.kt`,
`chat/ThermalBanner.kt`).

**Keep in `res/values/strings.xml` (NOT runtime-catalog):** `app_name` (manifest
references it at build/install) and notification **channel** names/descriptions
(read by the Android framework outside Compose). Notification *content* built in
Kotlin may move to the catalog. Document this boundary — coverage is not 100%.

## Delivery workflow

- **This is a planning-only pass — no feature code is written yet.** This document
  (committed straight to `main`, per the repo's doc-only norm) is the resumable
  spec.
- **When implementation begins** (separate, later session): do it on a **new feature
  branch** (e.g. `feature/i18n-string-catalog`), open a **PR**, and **do NOT merge
  until manually validated on the device** (Pixel 7). The on-device smoke in
  Verification is the merge gate. Never auto-merge / never merge with CI pending.
- Because the migration is phased and each phase is independently shippable, the PR
  may either land Phase 0+1 (infra + full EN extraction, English-only, no visible
  change) and follow with Phase 2, or stack the whole thing — decide at start of
  implementation.

## Migration sequencing (app compiles at every step)

- **Phase 0 — Infra, no behavior change.** Add all `i18n/` types, `I18nModule`,
  Android loader, `LocalStrings` seeded in `MainActivity` (nothing reads it yet),
  and an initially-tiny `strings_en.json`.
- **Phase 1 — EN extraction, lowest blast radius first:** (1) `AckPhrasePicker` +
  `VoiceCommand`; (2) `AgentLoop` constants + memory ack (thread `Strings` via
  `AgentLoopFactory`); (3) `PromptAssembler` blocks; (4) formatters one at a time
  (`TodoResponseFormatter` first, then the three `object→class` conversions); (5)
  Compose screens, one per change. Each step reproduces current English verbatim,
  so each is independently shippable.
- **Phase 2 — Add a real `strings_es.json`** to validate overlay/fallback/plurals
  end-to-end. No code change (this is the payoff: proves "new language = data file").

## Verification

- **Guardrail unit test** (commonMain): assert every `StringKeys` constant exists in
  `strings_en.json`; (optionally) flag EN keys never referenced (dead keys). For
  non-EN packs: assert keys are a **subset** of EN (no orphans) and every plural
  entry includes `other`.
- **PluralRules unit tests** against CLDR sample counts — esp. Russian (1→one,
  2→few, 5→many, 21→one, 11→many) and French (0,1→one).
- **Positional-formatter tests** incl. `%%` escaping and multi-arg ordering.
- **Dev-time missing-key behavior:** debug returns a loud `⟦missing:key⟧` + log;
  release returns the key (never crashes). Guardrail test makes EN misses
  impossible in CI.
- **On-device smoke (Pixel 7) — the merge gate:** build/install, confirm English UI
  unchanged after full migration (`./gradlew :androidApp:installDebug`); then drop a
  partial `strings_es.json`, flip Settings → language to Spanish, confirm translated
  keys switch live and untranslated keys fall back to English, and that
  deterministic cards (weather/clock/todo/finance), ack/voice phrases, and the
  system prompt all follow the setting.
- Existing formatter/PromptAssembler/AgentLoop tests updated to inject a test
  `Strings` (fake catalog) — keeps `:shared` Android-free.

## Pitfalls (carry into implementation)

1. The three `object` formatters can't be injected → must become classes (touches
   every call site + tests). Biggest mechanical cost.
2. Plural-rule correctness is the highest bug density — Russian few/many, French 0,
   CJK other-only. Unit-test hard.
3. No `String.format` positional specifiers from commonMain (iosMain) — hand-roll.
4. `strings.xml` can't be fully eliminated (`app_name`, notification channels).
5. Keep `:shared` Android-free: loader impl + Hilt only in `androidApp`/`androidMain`.
6. Resolve `Strings` once per turn; formatters/PromptAssembler run per turn — keep
   lookups off the per-token streaming path.
7. `ResponseFilter` / `TranslationIntentDetector` are unaffected (operate on model
   output bytes / user intent). A localized system prompt's script is already
   permitted by the same-language allow-list — consistent by construction.

## Deferred (tracked, not in this effort)

- Localize `locations.json` (country/region/city names) and `search_defaults.json`
  source `displayName`s — currently English-only; add native names or per-language
  asset variants later.
- `search_cache` is not locale-aware: `normalized_query` PK has no country/language
  tag, so changing location can serve stale cross-country results. Fix needs a
  `country_code` column / composite key → a new `.sqm` migration (invariant #20
  dance). Separate change.
- TTS voice currently uses `Locale.getDefault()`; consider driving it from
  `PreferredLanguage` so read-aloud matches the chosen language.
