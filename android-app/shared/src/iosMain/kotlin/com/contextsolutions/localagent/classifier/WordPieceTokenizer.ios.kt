package com.contextsolutions.localagent.classifier

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

/**
 * iOS NFD normalization for the WordPiece pipeline. Phase 2 work — the
 * file exists in Phase 1 so the expect/actual contract for iOS targets
 * compiles, mirroring the rest of `:shared/src/iosMain`.
 */
@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun unicodeNormalizeNfd(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping
