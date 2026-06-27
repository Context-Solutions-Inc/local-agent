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

// Build identity, computed at configuration time from the SAME git repo as the
// Android `BuildConfig` (androidApp/build.gradle.kts), so the desktop About shows
// the SAME version / build / git as mobile:
//   • versionCode = HEAD's committer-date epoch seconds (monotonic per commit)
//   • gitDescribe = short SHA + `-dirty` marker
//   • versionName = the release tag (kept in lockstep with androidApp appVersionName)
// `providers.exec` keeps this configuration-cache compatible; falls back outside a
// git checkout. Emitted as a classpath resource that DesktopAppBuildConfig reads.
val gitCommitTimestamp: Int =
    providers.exec { commandLine("git", "log", "-1", "--format=%ct", "HEAD") }
        .standardOutput.asText.get().trim().toIntOrNull() ?: 1
val appVersionName = "1.0.0"
val gitDescribe: String =
    providers.exec { commandLine("git", "describe", "--always", "--dirty", "--abbrev=7") }
        .standardOutput.asText.get().trim().ifEmpty { "unknown" }

// Compile-time-configurable hosting endpoint for the ONNX aux models (pre-flight
// classifier + MiniLM embedder). Override with `-PauxModelBaseUrl=https://host/path`
// (or set it in gradle.properties); blank ⇒ unconfigured, so the desktop app skips the
// download and the operator places the .onnx files manually. Baked into
// desktop_build_info.properties and read back by DesktopAuxModels (the sha256 + size
// are pinned in code; only the base URL is supplied here).
val auxModelBaseUrl: String = providers.gradleProperty("auxModelBaseUrl").orNull?.trim().orEmpty()

val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo")
val generateDesktopBuildInfo by tasks.registering(WriteProperties::class) {
    destinationFile.set(buildInfoDir.map { it.file("desktop_build_info.properties") })
    property("versionName", appVersionName)
    property("versionCode", gitCommitTimestamp)
    property("gitDescribe", gitDescribe)
    property("auxModelBaseUrl", auxModelBaseUrl)
}

sourceSets["main"].resources.srcDir(buildInfoDir)
tasks.named("processResources") { dependsOn(generateDesktopBuildInfo) }

// Bundle the `agent-jobs` git submodule (repo-root, alongside android-app/) into a
// single `agent-jobs.zip` classpath resource (PR #100). The desktop app extracts it
// to <app-data>/agent-jobs on first run / each new deployment (DesktopJobLibraryStore)
// so the Choose Job catalog has jobs to offer without a network fetch. Excludes the
// VCS dir and any user-generated state that may exist in a dev checkout (node_modules,
// credentials, the per-job seen/init markers) so the bundle stays small and an overlay
// extract never clobbers a user's runtime files.
val agentJobsDir = rootProject.projectDir.parentFile.resolve("agent-jobs")
val agentJobsResourcesDir = layout.buildDirectory.dir("generated/agentJobs")
val bundleAgentJobs by tasks.registering(Zip::class) {
    from(agentJobsDir) {
        exclude(
            "**/.git/**", ".git", ".git/**",
            "**/.claude/**",
            "**/node_modules/**",
            "**/.env",
            "**/seen.json",
            "**/.localagent-init.json",
        )
    }
    archiveFileName.set("agent-jobs.zip")
    destinationDirectory.set(agentJobsResourcesDir)
    // Deterministic archive (stable timestamps/order) so identical inputs hash the
    // same — keeps build caching/up-to-date checks honest.
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

sourceSets["main"].resources.srcDir(agentJobsResourcesDir)
tasks.named("processResources") { dependsOn(bundleAgentJobs) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    // Compose UI libs used directly by the tray + queue-status screen (Phase 7).
    // Direct CMP coordinates — `compose.foundation`/`compose.material3` aliases
    // deprecated as of CMP 1.10.
    implementation(libs.compose.mp.foundation)
    implementation(libs.compose.mp.material3)
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

// GPU offload (PR #55, Option 3): the LLM runs in a separate `llama-server` process
// (LlamaServerInferenceEngine), so GPU is selected by which prebuilt server archive is
// downloaded (CPU today; Vulkan/Metal/CUDA is the follow-up in LlamaServerRelease), NOT
// a JVM system property. The old `-PllamaLibPath` JNI hook is gone with the binding.

// Dev runs (`:desktopApp:run`) keep FULL diagnostic logging by setting the
// `localagent.debug` system property the gate (`DesktopDiag`) reads. This is scoped to the
// `run` JavaExec task ONLY — it is NOT added to `application.jvmArgs`, so a packaged
// production installer stays quiet by default. Operators can still opt a packaged build
// into verbose logging by launching it with `-Dlocalagent.debug=true`.
tasks.withType<JavaExec>().configureEach {
    if (name == "run") systemProperty("localagent.debug", "true")
}

compose.desktop {
    application {
        mainClass = "com.contextsolutions.localagent.desktop.app.MainKt"

        // The GGUF runs in the llama-server child process and ONNX classifier/embedder in
        // NATIVE (off-heap) memory, so the JVM heap stays modest — 2 GB covers the Compose
        // UI, image preprocessing, and ONNX tensor marshalling with headroom. Bump only if
        // a future on-heap path (e.g. large in-memory caches) needs it.
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            // jpackage builds ONLY for the host OS, so a CI matrix (Phase 8 increment 2)
            // produces one set per runner. Each OS declares both its installer formats:
            // Linux Deb/Rpm, macOS Dmg/Pkg, Windows Msi/Exe.
            targetFormats(
                TargetFormat.Deb, TargetFormat.Rpm,
                TargetFormat.Dmg, TargetFormat.Pkg,
                TargetFormat.Msi, TargetFormat.Exe,
            )
            packageName = "LocalAgent"
            // Installer/upgrade-detection version — kept in lockstep with the release tag
            // + app version (appVersionName, see androidApp appVersionName /
            // DesktopAppBuildConfig.versionName / the About dialog) so the produced
            // artifacts (e.g. local-agent_1.0.0-1_amd64.deb) match the in-app version.
            // The Compose plugin (and jpackage/macOS CFBundleVersion) require MAJOR > 0,
            // so the shared version MUST stay >= 1.x.
            packageVersion = appVersionName
            description = "On-device AI assistant — private, offline-capable chat, search, and memory."
            vendor = "Context Solutions Inc."
            copyright = "© 2026 Context Solutions Inc."

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
                packageName = "local-agent"        // dpkg/rpm package names are lowercase
                debMaintainer = "info@contextsolutions.ca"
                menuGroup = "Utility"
                appCategory = "Utility"
                appRelease = "1"
                rpmLicenseType = "MIT"
            }
            macOS {
                iconFile.set(iconsDir.resolve("icon.icns"))
                bundleID = "com.contextsolutions.localagent"
                appCategory = "public.app-category.productivity"
                // dmgPackageVersion/pkgPackageVersion default to packageVersion.

                // Code signing + notarization (docs/PRODUCTION_RUNBOOK.md).
                // GATED on credentials so the unsigned local/CI build still works (the
                // "CPU-default build that ALWAYS works" guarantee — Phase 8): signing
                // engages ONLY when MAC_SIGN_IDENTITY is exported. With it set,
                // `notarizeDmg`/`notarizePkg` sign with the Developer ID Application cert
                // and notarize via notarytool using the three NOTARIZATION_* env vars.
                //   MAC_SIGN_IDENTITY    = "Developer ID Application: Context Solutions (TEAMID)"
                //   NOTARIZATION_APPLE_ID, NOTARIZATION_PASSWORD (app-specific or @keychain:NAME),
                //   NOTARIZATION_TEAM_ID
                val macSignIdentity = providers.environmentVariable("MAC_SIGN_IDENTITY").orNull
                if (!macSignIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(macSignIdentity)
                    }
                    notarization {
                        appleID.set(providers.environmentVariable("NOTARIZATION_APPLE_ID"))
                        password.set(providers.environmentVariable("NOTARIZATION_PASSWORD"))
                        teamID.set(providers.environmentVariable("NOTARIZATION_TEAM_ID"))
                    }
                }
            }
            windows {
                iconFile.set(iconsDir.resolve("icon.ico"))
                menuGroup = "Local Agent"
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
