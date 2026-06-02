package com.contextsolutions.mobileagent.platform

/**
 * Desktop [AppBuildConfig] actual — a constant. The desktop build ships no
 * bundled dev secrets (operator supplies keys via SecureStorage / env, see
 * Phase 6), so the dev-key flags are always false. `isDebug` follows the
 * `mobileagent.debug` system property (default false) so a developer can opt
 * into debug-only affordances without a separate build.
 */
class DesktopAppBuildConfig : AppBuildConfig {
    override val isDebug: Boolean =
        System.getProperty("mobileagent.debug")?.toBoolean() ?: false
    override val isInternalBuild: Boolean = false
    override val hasBraveDevKey: Boolean = false
    override val hasHfDevToken: Boolean = false
    // User-facing release version = the git tag (v0.1.0). NOTE: the :desktopApp
    // nativeDistributions packageVersion stays 1.0.0 (the Compose plugin requires
    // MAJOR > 0 for the installer version) — that's the installer/upgrade version,
    // deliberately decoupled from this. Shown in the About dialog + backup metadata.
    override val versionName: String = "0.1.0"
    override val versionCode: Int = 1
    override val gitDescribe: String = "desktop"
}
