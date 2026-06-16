package com.contextsolutions.localagent.platform

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale as JLocale

// Mirrors the Android actual — both run on the JVM and read the host zone via
// java.time. Day-of-week / abbreviation are forced to English per SYSTEM_PROMPT.md §4.
actual class LocaleProvider {
    actual fun timeZoneId(): String = ZoneId.systemDefault().id

    actual fun timeZoneAbbreviation(): String? {
        val zone = ZoneId.systemDefault()
        val abbr = zone.getDisplayName(TextStyle.SHORT, JLocale.ENGLISH)
        return if (abbr.startsWith("GMT") || abbr.startsWith("UTC")) null else abbr
    }

    actual fun utcOffset(): String {
        val id = ZonedDateTime.now(ZoneId.systemDefault()).offset.id
        return if (id == "Z") "+00:00" else id
    }

    actual fun countryCode(): String = JLocale.getDefault().country.uppercase(JLocale.ROOT)
}
