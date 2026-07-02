import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared Compose Multiplatform UI for Android + desktop (docs/DESKTOP_PORT_PLAN.md).
// Phase 1 stands this up as a compiling shell with a placeholder screen; Phase 3
// migrates the real screens here one at a time. Same KMP-android plugin combo as
// :shared (AGP 9 forbids the old com.android.library + kotlin.multiplatform combo).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    android {
        namespace = "com.contextsolutions.localagent.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS (PR #41) — the shared Compose UI compiles to a `ComposeApp` framework
    // that the Xcode app (iosApp/) links + embeds (embedAndSignAppleFrameworkForXcode).
    // It re-exports :shared so Swift sees the exported Kotlin types (e.g.
    // NativeLlmBridge, initKoin). Additive: Android/desktop are untouched.
    //
    // DYNAMIC (isStatic = false) is load-bearing: the LiteRT-LM SwiftPM package forces
    // `-Xlinker -all_load` (its unsafeFlags) onto the whole app link, and Skiko bundles
    // its C libs (libjpeg/libicu/libpng/libwebp/libdng_sdk) twice in the framework
    // archive. With a STATIC framework, -all_load force-loads both copies of every
    // Skiko object → ~15.9k "duplicate symbol" link errors. A dynamic framework
    // resolves Skiko internally at framework-link time, so -all_load never reaches its
    // internals. See docs/IOS_BUILD.md.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = false
            export(project(":shared"))
            // The dynamic framework resolves all symbols at framework-link time, so it
            // must link the system SQLite that SQLDelight's NativeSqliteDriver (via
            // co.touchlab:sqliter) references — sqliter's cinterop linkerOpt doesn't
            // propagate to a consumer framework. Without this the link fails with
            // `Undefined symbols: _sqlite3_*`.
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` (not `implementation`) so the iOS `export(project(":shared"))`
            // above is legal — exported deps must be on the api configuration.
            api(project(":shared"))
            // Direct CMP artifact coordinates — the `compose.*` Gradle DSL aliases
            // are deprecated as of CMP 1.10 ("Specify dependency directly").
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.material3)
            implementation(libs.compose.mp.ui)
            // Material icons used by the migrated screens (Phase 9), the CMP analogue
            // of androidApp's `material-icons-extended`. Frozen at 1.7.3 upstream.
            implementation(libs.compose.mp.material.icons.extended)
            // Koin + the multiplatform Compose ViewModel integration: `module {}`
            // for `uiModule`, and `koinViewModel()` / `viewModelOf` (transitively
            // the JetBrains multiplatform `lifecycle-viewmodel` artifact, which
            // supplies `ViewModel`/`viewModelScope` in commonMain) for the
            // migrated screens' ViewModels.
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            // Migrated screens/ViewModels use kotlinx-datetime (relative date
            // labels, epoch-ms timestamps). :shared keeps it as `implementation`
            // (not exposed transitively), so :ui declares it directly. Pinned to
            // 0.6.1 below — see the resolutionStrategy note.
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            // Markdown + LaTeX on Android: the shared mikepenz Compose-Multiplatform
            // renderer (no WebView) + jlatexmath-android for the math images — the same
            // pure-Compose stack desktop + iOS use (invariant #41). The actual
            // `PlatformMarkdownMath` + `renderAndroidLatex` live in androidMain.
            implementation(libs.markdown.renderer.m3)
            implementation(libs.jlatexmath.android)
            // activity-compose `BackHandler` backs the Android `PlatformBackHandler`
            // actual (the system back gesture/button → route change).
            implementation(libs.androidx.activity.compose)
            // PR #57 — QR scanner (CameraX + drop-in scanner) for desktop pairing.
            implementation(libs.zxing.android.embedded)
        }
        val desktopMain by getting {
            dependencies {
                // Compose-Multiplatform markdown renderer + JLaTeXMath (Java2D →
                // ImageBitmap) for the desktop `PlatformMarkdownMath` actual.
                implementation(libs.markdown.renderer.m3)
                implementation(libs.jlatexmath)
                // PR #57 — QR encoder (pure-JVM) to render the desktop pairing QR.
                implementation(libs.zxing.core)
            }
        }
        iosMain.dependencies {
            // mikepenz multiplatform markdown renderer (publishes iOS artifacts)
            // for the iOS `PlatformMarkdownMath` actual. LaTeX renders as literal
            // text on iOS this milestone (no JLaTeXMath — JVM-only); PR #41.
            implementation(libs.markdown.renderer.m3)
        }
    }
}

// Compose Material3 (CMP) transitively pulls kotlinx-datetime 0.7.x, which RELOCATED
// `kotlinx.datetime.Clock` to `kotlin.time.Clock`. :shared compiles against 0.6.1 and the
// migrated screens call the old `kotlinx.datetime.Clock.System` / `Instant` API, so pin the
// resolved version to 0.6.1 (same pin as :desktopApp) until :shared migrates to the 0.7 API.
configurations.all {
    resolutionStrategy {
        force(libs.kotlinx.datetime)
    }
}
