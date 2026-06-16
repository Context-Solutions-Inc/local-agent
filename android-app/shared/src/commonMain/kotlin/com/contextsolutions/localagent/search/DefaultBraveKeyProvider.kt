package com.contextsolutions.localagent.search

import com.contextsolutions.localagent.platform.SecureStorage
import com.contextsolutions.localagent.platform.SecureStorageKeys

/**
 * Standard priority resolver: user-supplied key (from [SecureStorage]) overrides a
 * bundled dev key, blanks count as "no key." Used on every supported platform; the
 * platform layer is only responsible for sourcing [devKey] (e.g. Android reads it
 * from BuildConfig on debug builds, empty on release; iOS may read from an embedded
 * Info.plist field in Phase 2).
 */
class DefaultBraveKeyProvider(
    private val secureStorage: SecureStorage,
    private val devKey: String? = null,
) : BraveKeyProvider {

    override fun currentKey(): String? {
        val userKey = secureStorage.get(SecureStorageKeys.BRAVE_API_KEY)
        if (!userKey.isNullOrBlank()) return userKey
        return devKey?.takeIf { it.isNotBlank() }
    }
}
