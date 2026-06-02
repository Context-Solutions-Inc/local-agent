package com.contextsolutions.mobileagent.platform

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale as JLocale

actual class LocaleProvider {
    actual fun timeZoneId(): String = ZoneId.systemDefault().id

    actual fun timeZoneAbbreviation(): String? {
        val zone = ZoneId.systemDefault()
        // SHORT style returns a localized abbreviation (EDT, PST, JST). Falls back
        // to a numeric offset when no canonical abbreviation exists for the zone,
        // which we treat as "no abbreviation" so callers fall through to UTC offset.
        val abbr = zone.getDisplayName(TextStyle.SHORT, JLocale.ENGLISH)
        return if (abbr.startsWith("GMT") || abbr.startsWith("UTC")) null else abbr
    }

    actual fun utcOffset(): String {
        val offset = ZonedDateTime.now(ZoneId.systemDefault()).offset
        // ZoneOffset.getId() returns "Z" for UTC; normalize to "+00:00" for prompt consistency.
        val id = offset.id
        return if (id == "Z") "+00:00" else id
    }

    actual fun countryCode(): String = JLocale.getDefault().country.uppercase(JLocale.ROOT)
}
