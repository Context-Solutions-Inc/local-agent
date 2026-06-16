package com.contextsolutions.localagent.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.abbreviation
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT

/**
 * Phase 2 will replace this with a richer implementation that handles all the edge
 * cases (DST transitions, three-letter ambiguities). For Phase 1 the file exists only
 * to validate the expect/actual contract from commonMain compiles for iOS targets.
 */
actual class LocaleProvider {
    actual fun timeZoneId(): String = NSTimeZone.localTimeZone.name

    actual fun timeZoneAbbreviation(): String? = NSTimeZone.localTimeZone.abbreviation

    actual fun utcOffset(): String {
        val seconds = NSTimeZone.localTimeZone.secondsFromGMT.toInt()
        val sign = if (seconds >= 0) "+" else "-"
        val absSeconds = kotlin.math.abs(seconds)
        val hours = absSeconds / 3600
        val minutes = (absSeconds % 3600) / 60
        return "$sign${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
    }

    actual fun countryCode(): String =
        NSLocale.currentLocale.countryCode?.uppercase() ?: ""
}
