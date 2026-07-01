package com.contextsolutions.localagent.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * Reads small bundled text resources from the iOS app bundle via `NSBundle` — the iOS
 * counterpart of `DesktopResources.readTextOrNull` (classpath) / Android's asset reads.
 * Used for config/vocab that ships in the IPA rather than downloading: `vocab.txt`
 * (invariant #13), `search_defaults.json`, `locations.json`.
 *
 * Each file must be added to the `iosApp` target's **Copy Bundle Resources** build phase
 * (see docs/IOS_BUILD.md). Missing/unreadable → `null` and the caller falls back.
 */
@OptIn(ExperimentalForeignApi::class)
object IosResources {

    /** Read a bundled resource like `"search_defaults.json"`, or `null` if absent. */
    fun readTextOrNull(filename: String): String? {
        val dot = filename.lastIndexOf('.')
        val name = if (dot >= 0) filename.substring(0, dot) else filename
        val ext = if (dot >= 0) filename.substring(dot + 1) else ""
        val path = NSBundle.mainBundle.pathForResource(name, ofType = ext) ?: return null
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }
}
