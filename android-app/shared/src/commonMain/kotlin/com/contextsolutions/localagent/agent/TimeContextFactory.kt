package com.contextsolutions.localagent.agent

import com.contextsolutions.localagent.platform.AgentClock
import com.contextsolutions.localagent.platform.LocaleProvider

/**
 * Convenience factory so `:androidApp` can wire [PromptAssembler] without
 * importing kotlinx.datetime types directly. Reads the device clock + locale
 * each call (the assembler invokes this per turn).
 */
fun currentTimeContext(clock: AgentClock, localeProvider: LocaleProvider): TimeContext =
    TimeContext(
        now = clock.localNow(),
        timeZoneId = localeProvider.timeZoneId(),
        timeZoneAbbreviation = localeProvider.timeZoneAbbreviation(),
        utcOffset = localeProvider.utcOffset(),
    )
