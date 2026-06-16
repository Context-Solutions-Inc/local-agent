package com.contextsolutions.localagent.inference

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys

/**
 * Standard priority resolver: user-supplied token (from [SecureStorage])
 * overrides a bundled dev token; blanks count as "no token". Production callers
 * pass `devToken = null` (BuildConfig.HF_AUTH_TOKEN is empty on release anyway).
 */
class DefaultHfAuthTokenProvider(
    private val secureStorage: SecureStorage,
    private val devToken: String? = null,
) : HfAuthTokenProvider {

    override fun currentToken(): String? {
        val userToken = secureStorage.get(SecureStorageKeys.HF_AUTH_TOKEN)
        if (!userToken.isNullOrBlank()) return userToken
        return devToken?.takeIf { it.isNotBlank() }
    }
}
