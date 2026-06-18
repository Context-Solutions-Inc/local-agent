# Security Policy

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately to **security@contextsolutions.ca** with:

- a description of the issue and the component affected (Android app, desktop app, the relay/gateway
  path, the classifier-training pipeline, etc.);
- steps to reproduce or a proof of concept;
- the version / commit (`BuildConfig.GIT_DESCRIBE` is shown in the app's About dialog) and platform.

We aim to acknowledge a report within a few business days. Please give us a reasonable window to
investigate and ship a fix before any public disclosure.

## Scope

In scope: the Android and desktop apps in this repository, their data-at-rest and network-egress
behaviour, the job-execution subsystem, and the mobile↔desktop relay transport.

Out of scope: third-party services the app *optionally* talks to under user configuration (a
self-hosted Ollama/OpenAI-compatible server, the Brave Search API, HuggingFace model hosting), and the
Secure Gateway relay backend (tracked in its own repository). Trust assumptions for these are described
in the threat model.

## Supported surfaces

This is a pre-1.0 project (Phase 1 / pre-closed-beta). Fixes target the current `main` branch; there is
no back-port stream yet.

## Threat model

A full analysis — trust boundaries, data-at-rest, the complete network-egress enumeration, the
redaction/telemetry contract, and known/deferred hardening — lives in
**[docs/THREAT_MODEL.md](docs/THREAT_MODEL.md)**. Read it before filing a report so you can tell an
accepted, documented trade-off (e.g. the plaintext on-device database) from a genuine vulnerability.
