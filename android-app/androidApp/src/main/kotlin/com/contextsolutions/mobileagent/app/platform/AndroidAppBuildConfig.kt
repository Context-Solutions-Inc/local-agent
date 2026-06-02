package com.contextsolutions.mobileagent.app.platform

import com.contextsolutions.mobileagent.app.BuildConfig
import com.contextsolutions.mobileagent.platform.AppBuildConfig

/**
 * Android [AppBuildConfig] actual — reads the `:androidApp`-generated
 * `BuildConfig`. Lives in `:androidApp` (not `:shared` androidMain) because
 * `BuildConfig` is generated for this module only.
 */
class AndroidAppBuildConfig : AppBuildConfig {
    override val isDebug: Boolean = BuildConfig.DEBUG
    override val isInternalBuild: Boolean = BuildConfig.INTERNAL_BUILD
    override val hasBraveDevKey: Boolean =
        BuildConfig.INTERNAL_BUILD && BuildConfig.BRAVE_DEV_KEY.isNotBlank()
    override val hasHfDevToken: Boolean =
        BuildConfig.INTERNAL_BUILD && BuildConfig.HF_AUTH_TOKEN.isNotBlank()
    override val versionName: String = BuildConfig.VERSION_NAME
    override val versionCode: Int = BuildConfig.VERSION_CODE
    override val gitDescribe: String = BuildConfig.GIT_DESCRIBE
}
