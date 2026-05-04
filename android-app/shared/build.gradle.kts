import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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

    androidTarget {
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
            // On-device LLM runtime. Real LiteRtInferenceEngine implementation lands in
            // M1 (per docs/SPIKE_RUNBOOK.md Stage 2); for M0 the StubInferenceEngine in
            // androidApp/spike/ is what's bound via Hilt.
            implementation(libs.litertlm.android)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    namespace = "com.contextsolutions.mobileagent.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
        }
    }
}
