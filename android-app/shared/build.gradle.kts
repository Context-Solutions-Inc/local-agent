import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ONNX Runtime flavour for the desktop classifier/embedder. Default = CPU natives
// (headless-safe, bundled, always works). `-PonnxGpu=true` swaps in onnxruntime_gpu
// (CUDA Execution Provider) for an NVIDIA build (docs/DESKTOP_PACKAGING.md). The
// engines try the CUDA EP and fall back to CPU, so the GPU jar is a strict superset.
val onnxGpu = providers.gradleProperty("onnxGpu").orNull == "true"
val onnxRuntimeDep = if (onnxGpu) libs.onnxruntime.gpu else libs.onnxruntime

plugins {
    // AGP 9.0+ requires KMP libraries to use the new androidLibrary block exposed by
    // `com.android.kotlin.multiplatform.library` instead of `com.android.library`,
    // which AGP 9 explicitly forbids alongside kotlin.multiplatform. The new plugin
    // is applied IN ADDITION to kotlin.multiplatform — it doesn't replace it.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // expect/actual classes are still flagged as Beta in Kotlin 2.x even though every
    // major KMP project uses them. Opt in explicitly so the build log isn't noisy.
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    // Android target is configured via the new android block (provided by
    // com.android.kotlin.multiplatform.library) instead of the old combo of
    // kotlin.androidTarget + a top-level android { } block.
    android {
        namespace = "com.contextsolutions.localagent.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Desktop (JVM) target for the Linux/macOS/Windows port (docs/DESKTOP_PORT_PLAN.md).
    // Named "desktop" to disambiguate from the Android target, which also produces JVM
    // bytecode. commonMain compiles here unchanged; desktopMain supplies the JVM actuals
    // (Clock/Locale/NFD) plus the llama.cpp + CIO platform impls.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets are declared so the expect/actual contracts are exercised in Phase 1
    // even though iosMain bodies are stubs. Phase 2 fills these in.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            // Koin DI — `api` because the shared Koin module (di/AgentCoreModule.kt) is
            // public API returning Koin `Module` types, consumed by both app shells.
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.androidx.security.crypto)
            // Mobile relay SDK (MobileClient) for the E2EE relay transport. The
            // on-device AAR with lazysodium-android + a hardware-backed Android
            // Keystore is the remaining device step (CLAUDE.md #40 — verify the
            // libsodium .so doesn't collide with litert/litertlm natives).
            implementation(libs.securegateway.android)
            // PR #23 — vertical search preferences. Single-blob JSON storage
            // avoids the SQLDelight .sqm snapshot dance (invariant #20).
            implementation(libs.androidx.datastore.preferences)
            // On-device LLM runtime.
            implementation(libs.litertlm.android)
            // LiteRT-LM 0.10.2's Backend.GPU() requires Play Services TFLite to
            // expose OpenCL — without these the GPU backend fails with
            // "Cannot find OpenCL library on this device" on Pixel devices.
            // Matches the google-ai-edge/gallery reference setup.
            implementation(libs.play.services.tflite.java)
            implementation(libs.play.services.tflite.gpu)
            implementation(libs.play.services.tflite.support)
            // AI Edge LiteRT for the M4 pre-flight classifier — different
            // runtime from classic TFLite, ships its own libLiteRt.so and
            // matches the ai-edge-quantizer export tooling.
            implementation(libs.ai.edge.litert)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                // Pure-Kotlin coroutine HTTP engine (no OkHttp/Android).
                implementation(libs.ktor.client.cio)
                // PR #57 — mobile↔desktop link server (Ktor CIO server, pure-JVM,
                // no Netty natives; #40-safe). Hosts the OpenAI-compatible LLM
                // proxy + REST sync + QR pairing endpoints.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.server.status.pages)
                // JDBC SQLite driver for the desktop SQLDelight driver (Phase 6).
                implementation(libs.sqldelight.sqlite.driver)
                // Desktop LLM runtime is llama.cpp's `llama-server` subprocess over HTTP
                // (PR #55 Option 3, LlamaServerInferenceEngine via ktor-client-cio above) —
                // NOT a JNI binding. The net.ladenthin:llama JNI dep was removed: it drops
                // images before the vision encoder and ships CPU-only natives. The server
                // binary is downloaded/cached on first run (LlamaServerBinaryStore).
                // ONNX Runtime (Java) — desktop classifier + embedder (Phase 5). The
                // Android ai-edge-litert stack is Android-only (invariant #18), so the
                // DistilBERT 3-head classifier + MiniLM embedder are re-exported to ONNX.
                // CPU natives bundled by default; `-PonnxGpu=true` swaps in the
                // CUDA-EP build (onnxRuntimeDep, defined at the top of this file).
                implementation(onnxRuntimeDep)
                // Sentry JVM SDK — desktop crash reporting (Phase 7). Firebase
                // Crashlytics is Android-only (#23); the desktop SafeCrashReporter
                // is Sentry-backed, keeping the RedactedThrowable egress discipline (#24).
                implementation(libs.sentry.jvm)
                // Vosk — desktop offline STT (Phase 7). Bundles JNI natives; the
                // acoustic model is downloaded separately (VoskDictation no-ops without it).
                implementation(libs.vosk)
                // PR #70 — cron parsing / next-fire for the desktop job scheduler.
                // Desktop-only: the synced job model carries only the cronExpr string;
                // only the desktop interprets it (never in commonMain).
                implementation(libs.cron.utils)
                // PR #74 — Secure Gateway desktop client SDK (E2EE relay for paid
                // "anywhere access"). JVM-only (libsodium/JNA), consumed via mavenLocal.
                // Pulls in com.securegateway:core transitively. Used for the relay
                // pairing QR (DesktopClient.generatePairingQr); the relay transport
                // itself is the stubbed follow-up (RelayLinkTransport).
                implementation(libs.securegateway.java)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                // Runtime ORT for the numeric-parity test (Phase 5). The engines are
                // in desktopMain; the test drives them, so ORT must be on the test
                // runtime classpath to actually load a model. Matches the main flavour.
                implementation(onnxRuntimeDep)
            }
            // ONNX numeric-parity fixtures (classifier_onnx_/embedder_onnx_), staged
            // from classifier-training/tests/fixtures by collectOnnxParityFixtures.
            resources.srcDir(layout.buildDirectory.dir("generated/onnxParityFixtures"))
        }
    }
}

// Stage the ONNX numeric-parity fixtures onto the desktopTest classpath. They're
// generated by ct-export-onnx / export_minilm_onnx.py on the operator's box; absent
// in CI, so OnnxEngineParityTest skips when the .onnx models aren't present. Copy
// tolerates the missing sources (contributes nothing) until the operator runs them.
val collectOnnxParityFixtures = tasks.register<Copy>("collectOnnxParityFixtures") {
    description = "Stage ONNX parity fixtures for :shared desktopTest."
    from(rootDir.resolve("../classifier-training/tests/fixtures/classifier_onnx_canonical_outputs.json"))
    from(rootDir.resolve("../classifier-training/tests/fixtures/embedder_onnx_canonical_outputs.json"))
    into(layout.buildDirectory.dir("generated/onnxParityFixtures"))
}
tasks.matching { it.name == "desktopTestProcessResources" || it.name == "processDesktopTestResources" }
    .configureEach { dependsOn(collectOnnxParityFixtures) }

// litert-2.1.4.aar bundles its own copy of `org.tensorflow.lite.*` classes
// (InterpreterApi, Tensor, etc.) directly inside classes.jar.
// play-services-tflite-java transitively pulls `tensorflow-lite-api:2.16.x`
// with the same fully-qualified class names — AGP rejects the duplicates.
// Excluding the transitive tensorflow-lite-api from every androidMain
// configuration makes litert's bundled copy the canonical source for
// `org.tensorflow.lite.*`. Both versions are ABI-compatible: LiteRT-LM
// (Gemma path) only calls Play Services TFLite via the InterpreterApi
// surface at runtime, so it doesn't matter which classloader-visible
// copy of the interface it links against, as long as exactly one is
// present.
configurations.matching {
    it.name.startsWith("androidMain") || it.name.startsWith("androidTest") ||
        it.name.startsWith("debugAndroid") || it.name.startsWith("releaseAndroid")
}.configureEach {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
}

sqldelight {
    databases {
        create("LocalAgentDatabase") {
            packageName.set("com.contextsolutions.localagent.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
            // SQLite 3.25+ for UPSERT (`ON CONFLICT ... DO UPDATE`) in TelemetryAggregate.sq.
            // Default 3.18 dialect is older than what Android 16's bundled SQLite supports,
            // and older than the 3.25 floor we actually need.
            dialect(libs.sqldelight.sqlite.dialect)
            // Schema snapshots live next to the .sq + .sqm files. verifyMigrations
            // (enabled below) walks .sqm files forward from the snapshot and compares
            // the result against the .sq schema. Generated via
            // `./gradlew :shared:generateLocalAgentDatabaseSchema` after a clean v1
            // state is checked in (one-time bootstrap; subsequent versions auto-update).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // Build-time schema-drift gate. Re-enabled in Phase A once the v1 snapshot
            // exists; without it, the task short-circuits silently because there's
            // nothing to verify against. M6 Phase A.
            verifyMigrations.set(true)
        }
    }
}
