package com.contextsolutions.localagent.platform

/**
 * Cross-platform view of the host app's build flags (docs/DESKTOP_PORT_PLAN.md
 * Phase 9). The `:androidApp` `BuildConfig` is generated per-module and is not
 * visible from shared `:ui` commonMain, so screens/ViewModels that gate on
 * build flags read them through this Koin-bound seam instead.
 *
 * Android binds an actual backed by `BuildConfig`; desktop binds a constant.
 */
interface AppBuildConfig {
    /** True for a debuggable build — gates debug-only UI affordances. */
    val isDebug: Boolean

    /** True for internal/dogfood builds that may carry bundled dev secrets. */
    val isInternalBuild: Boolean

    /** True when an internal build bundles a Brave dev API key. */
    val hasBraveDevKey: Boolean

    /** Human-readable app version (e.g. "1.0.0"), recorded in memory-backup metadata. */
    val versionName: String

    /** Numeric build code (Android: VERSION_CODE = HEAD commit timestamp). Shown in About. */
    val versionCode: Int

    /** `git describe` identity (SHA + `-dirty`), shown in About to disambiguate dev builds. */
    val gitDescribe: String
}
