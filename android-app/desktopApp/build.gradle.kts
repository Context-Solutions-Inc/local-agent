import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Thin Compose Desktop shell for the Linux/macOS/Windows app (docs/DESKTOP_PORT_PLAN.md).
// Phase 1: opens a window rendering the shared :ui placeholder. Later phases add the
// tray, queued-task system, Koin wiring, and the migrated screens.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    // Compose UI libs used directly by the tray + queue-status screen (Phase 7).
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    // Provides Dispatchers.Main (Swing EDT) on desktop — required by androidx
    // ViewModel's viewModelScope (Dispatchers.Main.immediate). Without it the
    // shared :ui ViewModels throw "Module with the Main dispatcher is missing"
    // the moment a screen composes. Must match the coroutines-core version.
    implementation(libs.kotlinx.coroutines.swing)
    // :shared keeps kotlinx-datetime as `implementation`; the desktop app hosts the real
    // AgentLoop (Clock.System default for nowEpochMs), so it needs datetime at runtime.
    implementation(libs.kotlinx.datetime)
}

// Compose Material3 1.9.0 (via :ui) transitively pulls kotlinx-datetime 0.7.x, which RELOCATED
// `kotlinx.datetime.Clock` to `kotlin.time.Clock`. :shared compiles against 0.6.1 and calls the
// old `kotlinx.datetime.Clock.System`, so conflict-resolution to 0.7.x makes any real agent turn
// crash with NoClassDefFoundError. Pin the runtime to the version :shared was compiled against
// until :shared migrates to the 0.7 Clock API.
configurations.all {
    resolutionStrategy {
        force(libs.kotlinx.datetime)
    }
}

// Opt-in GPU offload for the LLM (deferred-by-default, BYO native — see
// docs/DESKTOP_PACKAGING.md "GPU acceleration"). The bundled net.ladenthin:llama
// libjllama is CPU-only; `-PllamaLibPath=/abs/dir` points the binding's loader
// (system property `net.ladenthin.llama.lib.path`) at a GPU-enabled libjllama.so
// the operator built/supplied. The engine already requests full offload
// (InferenceConfig.accelerator defaults to AUTO → setGpuLayers(999)), so dropping
// in a CUDA/Metal/Vulkan native is all that's needed — no code change. Applies to
// both `:desktopApp:run` and the packaged app (compose jvmArgs cover both).
//   ./gradlew :desktopApp:run -PllamaLibPath=/abs/dir/with/libjllama.so
val llamaLibPath = (project.findProperty("llamaLibPath") as String?)?.takeIf { it.isNotBlank() }

compose.desktop {
    application {
        mainClass = "com.contextsolutions.mobileagent.desktop.app.MainKt"

        // The GGUF model + ONNX classifier/embedder run in NATIVE (off-heap) memory via
        // llama.cpp / ONNX Runtime, so the JVM heap stays modest — 2 GB covers the Compose
        // UI, image preprocessing, and ONNX tensor marshalling with headroom. Bump only if
        // a future on-heap path (e.g. large in-memory caches) needs it.
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")
        llamaLibPath?.let { jvmArgs += "-Dnet.ladenthin.llama.lib.path=$it" }

        nativeDistributions {
            // jpackage builds ONLY for the host OS, so a CI matrix (Phase 8 increment 2)
            // produces one set per runner. Each OS declares both its installer formats:
            // Linux Deb/Rpm, macOS Dmg/Pkg, Windows Msi/Exe.
            targetFormats(
                TargetFormat.Deb, TargetFormat.Rpm,
                TargetFormat.Dmg, TargetFormat.Pkg,
                TargetFormat.Msi, TargetFormat.Exe,
            )
            packageName = "MobileAgent"
            // Installer/upgrade-detection version ONLY — deliberately decoupled from the
            // release tag + app version (v0.1.0, see androidApp appVersionName /
            // DesktopAppBuildConfig.versionName / the About dialog). The Compose plugin
            // (and jpackage/macOS CFBundleVersion) require MAJOR > 0, so a 0.x value is
            // rejected at configuration time — the installer's internal version stays
            // 1.0.0 while the user-facing version is 0.1.0. Bump in lockstep once the
            // release reaches 1.x.
            packageVersion = "1.0.0"
            description = "On-device AI assistant — private, offline-capable chat, search, and memory."
            vendor = "Context Solutions"
            copyright = "© 2026 Context Solutions"

            // Ship the full JDK runtime image rather than a jlink-minimised one. The app pulls
            // in modules that are easy to miss when probing (java.sql for the SQLite JDBC driver,
            // jdk.management for the OS MXBean headroom probe, java.desktop for Swing file
            // chooser + javax.sound capture, jdk.crypto.ec for TLS to Brave). The extra runtime
            // weight is trivial next to the multi-GB model the app downloads at first run, and it
            // keeps the "CPU-default build that ALWAYS works" guarantee (plan Phase 8).
            includeAllModules = true

            val iconsDir = project.file("icons")

            linux {
                iconFile.set(iconsDir.resolve("icon.png"))
                packageName = "mobile-agent"        // dpkg/rpm package names are lowercase
                debMaintainer = "lawrence.ley@contextsolutions.ca"
                menuGroup = "Utility"
                appCategory = "Utility"
                appRelease = "1"
                rpmLicenseType = "Proprietary"
            }
            macOS {
                iconFile.set(iconsDir.resolve("icon.icns"))
                bundleID = "com.contextsolutions.mobileagent"
                appCategory = "public.app-category.productivity"
                // dmgPackageVersion/pkgPackageVersion default to packageVersion.
            }
            windows {
                iconFile.set(iconsDir.resolve("icon.ico"))
                menuGroup = "Mobile Agent"
                // STABLE across releases — MSI uses it to recognise upgrades vs fresh installs.
                // Never regenerate this once shipped.
                upgradeUuid = "b6c3f2a4-1e5d-4a7c-9f0b-2d8e6c1a4f33"
                perUserInstall = true               // no admin rights needed
                dirChooser = true                   // let users pick the install dir
                shortcut = true
            }
        }
    }
}
