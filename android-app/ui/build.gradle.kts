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
        namespace = "com.contextsolutions.mobileagent.ui"
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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Material icons used by the migrated screens (Phase 9). The
            // multiplatform `compose.materialIconsExtended` is the CMP analogue
            // of androidApp's `material-icons-extended`.
            implementation(compose.materialIconsExtended)
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
            // Markdown + LaTeX on Android: Markwon + ext-latex (jlatexmath, native
            // canvas, no WebView) — the same stack androidApp's MarkdownMathText
            // uses (invariant #41). The actual `PlatformMarkdownMath` lives here.
            implementation(libs.markwon.core)
            implementation(libs.markwon.ext.latex)
            implementation(libs.markwon.inline.parser)
            // activity-compose `BackHandler` backs the Android `PlatformBackHandler`
            // actual (the system back gesture/button → route change).
            implementation(libs.androidx.activity.compose)
        }
        val desktopMain by getting {
            dependencies {
                // Compose-Multiplatform markdown renderer + JLaTeXMath (Java2D →
                // ImageBitmap) for the desktop `PlatformMarkdownMath` actual.
                implementation(libs.markdown.renderer.m3)
                implementation(libs.jlatexmath)
            }
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
