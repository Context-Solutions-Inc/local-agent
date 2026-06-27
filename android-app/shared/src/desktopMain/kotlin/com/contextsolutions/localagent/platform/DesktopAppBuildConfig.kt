package com.contextsolutions.localagent.platform

import java.util.Properties

/**
 * Desktop [AppBuildConfig] actual. The desktop build ships no bundled dev secrets
 * (operator supplies keys via SecureStorage / env, see Phase 6), so the dev-key
 * flags are always false. `isDebug` follows the `localagent.debug` system property
 * (default false) so a developer can opt into debug-only affordances without a
 * separate build.
 *
 * Version / build / git are read from `desktop_build_info.properties`, generated
 * at build time by `:desktopApp` from the SAME git repo as the Android
 * `BuildConfig`, so the desktop About shows the SAME values as mobile. The
 * fallbacks apply only when that resource is absent (e.g. a bare unit-test
 * classpath). NOTE: the :desktopApp nativeDistributions packageVersion stays 1.0.0
 * (the Compose plugin requires MAJOR > 0 for the installer version) — that's the
 * installer/upgrade version, deliberately decoupled from versionName.
 */
class DesktopAppBuildConfig : AppBuildConfig {
    override val isDebug: Boolean =
        System.getProperty("localagent.debug")?.toBoolean() ?: false
    override val isInternalBuild: Boolean = false
    override val hasBraveDevKey: Boolean = false

    private val buildInfo: Properties = Properties().apply {
        DesktopAppBuildConfig::class.java
            .getResourceAsStream("/desktop_build_info.properties")
            ?.use { load(it) }
    }

    override val versionName: String = buildInfo.getProperty("versionName") ?: "1.0.0"
    override val versionCode: Int = buildInfo.getProperty("versionCode")?.toIntOrNull() ?: 1
    override val gitDescribe: String = buildInfo.getProperty("gitDescribe") ?: "unknown"
}
