import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        namespace = "com.contextsolutions.mobileagent.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
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
    }
}

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
        create("MobileAgentDatabase") {
            packageName.set("com.contextsolutions.mobileagent.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
            // SQLite 3.25+ for UPSERT (`ON CONFLICT ... DO UPDATE`) in TelemetryAggregate.sq.
            // Default 3.18 dialect is older than what Android 16's bundled SQLite supports,
            // and older than the 3.25 floor we actually need.
            dialect(libs.sqldelight.sqlite.dialect)
            // Schema snapshots live next to the .sq + .sqm files. verifyMigrations
            // (enabled below) walks .sqm files forward from the snapshot and compares
            // the result against the .sq schema. Generated via
            // `./gradlew :shared:generateMobileAgentDatabaseSchema` after a clean v1
            // state is checked in (one-time bootstrap; subsequent versions auto-update).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // Build-time schema-drift gate. Re-enabled in Phase A once the v1 snapshot
            // exists; without it, the task short-circuits silently because there's
            // nothing to verify against. M6 Phase A.
            verifyMigrations.set(true)
        }
    }
}
