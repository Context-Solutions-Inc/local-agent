# Contributing to Local Agent

Thanks for working on Local Agent. This guide covers how the project is organized, the conventions
that keep it coherent, and how to get a change landed. Read it alongside **[CLAUDE.md](CLAUDE.md)**,
which holds the hard-won invariants the code depends on.

## Ground rules

These are the working norms that keep the codebase healthy:

- **Don't commit or push unless asked.** Changes are reviewed as diffs; let the reviewer decide what
  lands. Never commit on the default branch directly — branch first.
- **Keep platform runtimes behind their seams.** The `InferenceEngine` interface in `commonMain` is
  the seam for *all* LLM backends (LiteRT-LM, llama-server, Ollama, relay, stub) — never special-case a
  backend above it. The same discipline applies to other expect/actual seams (voice, vision, secure
  storage, notifications). LiteRT-LM / llama.cpp / ONNX / Firebase / Sentry / Secure-Gateway types must
  not leak into `commonMain`.
- **Read the official integration guide before introspecting a JAR.** For LiteRT-LM, the
  [Android guide](https://ai.google.dev/edge/litert-lm/android) documents intended usage you can't
  reverse-engineer from signatures; *then* use `javap -p` for exact signatures.
- **Documentation hygiene.** When a Phase-1 decision changes, update both
  [PHASE1_PLAN.md](PHASE1_PLAN.md) and [docs/M0_DECISION_MEMO.md](docs/M0_DECISION_MEMO.md). When you
  change behaviour the README/USER_GUIDE/BUILD docs describe, update those too.
- **Respect the privacy contract.** User text (chat, memory, search queries) must never appear in
  telemetry, crash reports, exception messages, or breadcrumbs. See
  [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and the redaction invariants (#24–#28).

## The hard-invariant model

The codebase carries a set of **numbered hard invariants** in [CLAUDE.md](CLAUDE.md) — non-obvious
constraints discovered the hard way (native-lib collisions, classifier tensor ordering, cancellation
semantics, search routing, sync trust boundaries, …). The numbering is **stable**, and code/comments
reference invariants by number (e.g. "see invariant #40"). Before changing anything in inference,
the classifier/embedder runtime, search routing, the build's native-lib packaging, sync, or the relay:

1. Search CLAUDE.md for the relevant invariant number(s).
2. If your change invalidates an invariant, update the invariant text in the same PR and explain why.
3. If you discover a *new* non-obvious constraint, add a numbered invariant rather than burying the
   knowledge in a commit message.

## Project layout

```
android-app/        The Gradle project (KMP). Modules: :shared, :ui, :androidApp, :desktopApp, :desktopHarness
classifier-training/ Python ML pipeline (dataset gen, training, quantization, ONNX/LiteRT export, ct-* CLIs)
datasets/           JSONL dataset manifests (payloads gitignored, manifests committed)
eval/               Regression harnesses + canonical query suites (drive the prompt-eval gate)
models/             .tflite/.onnx artifacts (gitignored; model cards live in docs/)
docs/               Decision memos, milestone plans, handoffs, model cards, runbooks
agent-jobs/         Git submodule — the desktop job library (bundled into the desktop build)
```

Where code for a concern lives (all under `android-app/shared/src/commonMain/kotlin/.../`, with
platform actuals in `androidMain`/`desktopMain`):

| Concern | Package |
|---|---|
| Agent loop, prompt assembly | `agent/` |
| Inference engines + routing | `inference/` |
| Pre-flight classifier + routing | `classifier/` |
| Web search + verticals | `search/`, `search/vertical/` |
| Memory (extract/retrieve/embed) | `memory/` |
| Desktop jobs | `job/` |
| Voice (STT/TTS) | `voice/` |
| Mobile↔desktop relay transport | `link/` |
| Paid relay subscription | `subscription/` |
| Telemetry + crash redaction | `telemetry/`, `observability/` |
| Secure storage, DB, HTTP | `platform/` |

UI screens are in `:ui` (Compose Multiplatform). See [docs/DESKTOP_PORT_PLAN.md](docs/DESKTOP_PORT_PLAN.md)
for the module architecture.

## Dev environment

Setup, build commands, flags, the Docker container, and the classifier-training venv are all in
**[docs/BUILD.md](docs/BUILD.md)**. In short:

```bash
git clone --recurse-submodules <repo-url>     # or: git submodule update --init
cd local-agent/android-app
./gradlew :androidApp:installDebug            # Android (needs secrets.properties + a Pixel 7)
./gradlew :desktopApp:run                     # Desktop (JDK 17 only)
```

## Testing

- **JVM unit tests:** `./gradlew test`. On Linux, Kotlin/Native (iOS) targets can't compile, so a full
  `./gradlew check` needs `-x :shared:compileKotlinIosX64 -x :shared:compileKotlinIosSimulatorArm64`.
- **Quick wiring check (desktop):** `DI_CHECK=1 ./gradlew :desktopApp:run` resolves the whole Koin
  graph headlessly.
- **Classifier regression gate:** any new `.tflite` in `models/` must pass `ct-regression-check`
  (`.github/workflows/regression-gate.yml`). Routing/prompt changes run the 15-query `CanonicalEvalTest`
  (`prompt-eval-gate.yml`).
- **On-device verification is mandatory for native-lib changes.** A compile-only `assembleDebug` will
  NOT catch a `libLiteRt.so` packaging regression (invariant #40) or a classifier numerics regression
  (#18). Install on a Pixel 7 and confirm the classifier/embedder/Gemma load. The instrumentation
  benchmarks (`ClassifierLatencyBenchmark`, `ClassifierEndToEndTest`, `EmbedderEndToEndTest`) run via
  `./gradlew :androidApp:connectedDebugAndroidTest`.
- New behaviour should ship with tests. The privacy canaries (`TelemetryPayloadBuilderTest`,
  `TelemetryPayloadBuilderTest` PII assertions) are load-bearing — don't weaken them.

## Code style

- Kotlin official style (`kotlin.code.style=official`); match the surrounding code's idiom, naming, and
  comment density.
- Comments should explain *why* (especially anything that encodes an invariant), not restate the code.

## Commits & PRs

- Branch off `main`; keep PRs focused. The reviewer reads diffs, so a clear description of intent and
  any invariant you touched is more useful than a prose recap of the code.
- **Never commit secrets.** `secrets.properties`, `google-services.json`, `local.properties`, model
  artifacts, and keystores are gitignored — keep them that way.
- If you change a submodule pointer (`agent-jobs`), call it out explicitly in the PR.
- Update the relevant docs in the same PR (see Documentation hygiene above).

## Security issues

Do **not** open a public issue for a vulnerability — follow [SECURITY.md](SECURITY.md).

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE). By contributing, you agree
that your contributions are licensed under the same terms.
