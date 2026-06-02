package com.contextsolutions.mobileagent.platform

/**
 * Locale and timezone facts read from the device, not from user settings.
 *
 * Per SYSTEM_PROMPT.md section 4, the timezone reflects the device's current location
 * (handles travel + DST). Day-of-week names are always English regardless of UI locale —
 * Gemma 4 was trained on English day names and localizing degrades interpretation.
 */
expect class LocaleProvider() {
    /** IANA timezone identifier, e.g. "America/Toronto". */
    fun timeZoneId(): String

    /** Common abbreviation if available (EDT, PST, JST), else null. */
    fun timeZoneAbbreviation(): String?

    /** UTC offset in ±HH:MM form, e.g. "-04:00". */
    fun utcOffset(): String

    /**
     * ISO 3166-1 alpha-2 country code of the device locale (e.g. "US", "CA"),
     * uppercased, or "" when unknown. Used by the onboarding location picker to
     * pre-select a sensible default country.
     */
    fun countryCode(): String
}
