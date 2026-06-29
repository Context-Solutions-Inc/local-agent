package com.contextsolutions.localagent.i18n

import com.contextsolutions.localagent.i18n.StringValue.Listed
import com.contextsolutions.localagent.i18n.StringValue.Plural
import com.contextsolutions.localagent.i18n.StringValue.Simple

/**
 * The English [StringPack] — the always-present fallback floor. English is held
 * in code (not a bundled `strings_en.json`) so it can never fail to load and is
 * complete by construction: the guardrail test asserts this map's keys equal
 * [StringKeys.ALL]. New languages ship as pure JSON data files parsed by
 * [StringPack.parse] and overlaid on this floor (the "new language = data file"
 * goal); only the floor is in code.
 *
 * Values are byte-identical to the literals they replaced in the formatters and
 * `AgentLoop`, so the migration is behaviour-preserving for English.
 */
object EnglishStrings {

    val pack: StringPack by lazy {
        StringPack(code = "en", values = values, plurals = PluralRules.ENGLISH)
    }

    private val values: Map<String, StringValue> = mapOf(
        // ── Shared ──
        StringKeys.COMMON_DONE to Simple("Done."),
        StringKeys.COMMON_ERROR_GENERIC to Simple("Sorry, that didn't work: %1\$s"),
        StringKeys.COMMON_UNKNOWN_ERROR to Simple("unknown error"),
        StringKeys.COMMON_SOURCE to Simple("Source: "),

        // ── Agent loop ──
        StringKeys.AGENT_ENGINE_ERROR to
            Simple("Sorry, I had trouble processing that request. Please try again."),

        // ── Clock ──
        StringKeys.CLOCK_GUIDANCE to Simple(
            "Sorry, I didn't quite understand that clock command. Try " +
                "phrasings like \"set a 5 minute timer\", \"set an alarm for " +
                "7am every weekday\", \"cancel my tea timer\", or \"what " +
                "alarms do I have\".",
        ),
        StringKeys.CLOCK_ALARMS_NONE to Simple("You don't have any alarms set."),
        StringKeys.CLOCK_ALARMS_ONE to Simple("You have one alarm set: %1\$s."),
        StringKeys.CLOCK_ALARMS_HEADER to Simple("You have %1\$d alarms set:"),
        StringKeys.CLOCK_TIMERS_NONE to Simple("You don't have any timers running."),
        StringKeys.CLOCK_TIMERS_ONE to Simple("You have one timer running: %1\$s."),
        StringKeys.CLOCK_TIMERS_HEADER to Simple("You have %1\$d timers running:"),
        StringKeys.CLOCK_ALARM_SET to Simple("Alarm set for "),
        StringKeys.CLOCK_TIMER_SET to Simple("Timer set for "),
        StringKeys.CLOCK_TIMER_REMAINING to Simple("%1\$s remaining"),
        StringKeys.CLOCK_CANCELLED_ALARMS to Plural(
            mapOf("one" to "Cancelled %1\$d alarm.", "other" to "Cancelled %1\$d alarms."),
        ),
        StringKeys.CLOCK_CANCELLED_TIMERS to Plural(
            mapOf("one" to "Cancelled %1\$d timer.", "other" to "Cancelled %1\$d timers."),
        ),
        StringKeys.CLOCK_DURATION_ZERO to Simple("0 seconds"),
        StringKeys.CLOCK_DURATION_HOURS to Plural(
            mapOf("one" to "%1\$d hour", "other" to "%1\$d hours"),
        ),
        StringKeys.CLOCK_DURATION_MINUTES to Plural(
            mapOf("one" to "%1\$d minute", "other" to "%1\$d minutes"),
        ),
        StringKeys.CLOCK_DURATION_SECONDS to Plural(
            mapOf("one" to "%1\$d second", "other" to "%1\$d seconds"),
        ),

        // ── My List ──
        StringKeys.MYLIST_GUIDANCE to Simple(
            "Sorry, I didn't quite understand that list command. Try " +
                "phrasings like \"add buy milk to my list\", \"add finish " +
                "report with high priority by tomorrow to my list\", \"show " +
                "my list\", \"complete #2 on my list\", \"remove the gym item " +
                "from my list\", or \"set #1 to high priority on my list\". " +
                "Due dates accept today, tomorrow, or an ISO date like 2026-05-20.",
        ),
        StringKeys.MYLIST_ADDED to Simple("Added \"%1\$s\""),
        StringKeys.MYLIST_DUE to Simple("due %1\$s"),
        StringKeys.MYLIST_PRIORITY_HIGH to Simple("High"),
        StringKeys.MYLIST_PRIORITY_MEDIUM to Simple("Medium"),
        StringKeys.MYLIST_PRIORITY_LOW to Simple("Low"),
        StringKeys.MYLIST_PRIORITY_TAG to Simple("priority %1\$s"),
        StringKeys.MYLIST_NONE_ALL to Simple("Your list is empty."),
        StringKeys.MYLIST_NONE_OPEN to Simple("You don't have any open items."),
        StringKeys.MYLIST_ONE_HEADER to Simple("You have one item:"),
        StringKeys.MYLIST_HEADER to Simple("You have %1\$d items:"),
        StringKeys.MYLIST_DONE_MARKER to Simple("[done]"),
        StringKeys.MYLIST_MARKED_DONE to Simple("Marked \"%1\$s\" as done."),
        StringKeys.MYLIST_REOPENED to Simple("Reopened \"%1\$s\"."),
        StringKeys.MYLIST_DELETED to Simple("Deleted \"%1\$s\"."),
        StringKeys.MYLIST_UPDATED to Simple("Updated \"%1\$s\""),
        StringKeys.MYLIST_CLEAR_NONE to Simple("No completed items to clear."),
        StringKeys.MYLIST_CLEARED to Plural(
            mapOf(
                "one" to "Cleared %1\$d completed item.",
                "other" to "Cleared %1\$d completed items.",
            ),
        ),
        StringKeys.MYLIST_DUE_TODAY to Simple("today"),
        StringKeys.MYLIST_DUE_TOMORROW to Simple("tomorrow"),
        StringKeys.MYLIST_DUE_YESTERDAY to Simple("yesterday"),

        // ── Weather ──
        StringKeys.WEATHER_LOCATION_PROMPT to Simple(
            "Which city would you like the weather for? Tell me the city and " +
                "state or province — for example, \"weather in Miami, Florida\" " +
                "or \"weather in Toronto, Ontario\".",
        ),
        StringKeys.WEATHER_HEADER to Simple("Weather for %1\$s"),
        StringKeys.WEATHER_YOUR_AREA to Simple("your area"),
        StringKeys.WEATHER_ALERT_WARNING to Simple("%1\$s warning in effect"),
        StringKeys.WEATHER_ALERT_WATCH to Simple("%1\$s watch in effect"),
        StringKeys.WEATHER_ALERT_ADVISORY to Simple("%1\$s advisory in effect"),
        StringKeys.WEATHER_ALERT_STATEMENT to Simple("%1\$s statement issued"),
        StringKeys.WEATHER_ALERT_GENERIC to Simple("%1\$s alert in effect"),
        StringKeys.WEATHER_NOW_PREFIX to Simple("Now: "),
        StringKeys.WEATHER_HUMIDEX_LABEL to Simple("humidex"),
        StringKeys.WEATHER_WIND to Simple("Wind %1\$s"),
        StringKeys.WEATHER_HUMIDITY to Simple("Humidity %1\$s%%"),
        StringKeys.WEATHER_UPDATED to Simple("Updated"),
        StringKeys.WEATHER_DISAMBIGUATION to Simple(
            "There's more than one place called %1\$s. " +
                "Did you mean %2\$s? Tell me which — for example, \"weather in %3\$s\".",
        ),

        // ── Stock ──
        StringKeys.STOCK_FALLBACK_NAME to Simple("Stock"),
        StringKeys.STOCK_DAY_RANGE to Simple("Day %1\$s–%2\$s"),
        StringKeys.STOCK_WEEK52_RANGE to Simple("52-wk %1\$s–%2\$s"),
        StringKeys.STOCK_MARKET_CAP to Simple("Mkt cap %1\$s"),
        StringKeys.STOCK_PE to Simple("P/E %1\$s"),
        StringKeys.STOCK_VOLUME to Simple("Vol %1\$s"),
        StringKeys.STOCK_AS_OF to Simple("As of %1\$s"),

        // ── Jobs (inline run) ──
        StringKeys.JOB_RUN_PROMPT to Simple(
            "Tell me which job to run, e.g. \"run job <job name> <keywords>\".",
        ),
        StringKeys.JOB_NOT_FOUND to Simple(
            "I couldn't find a job named \"%1\$s\". Check the Jobs list for the exact name.",
        ),
        StringKeys.JOB_NO_OUTPUT to Simple("(no output)"),
        StringKeys.JOB_FAILED to Simple("The job \"%1\$s\" didn't complete successfully."),

        // ── Memory acks ──
        StringKeys.MEMORY_ACK_REMEMBER to Simple("OK, I'll remember that."),
        StringKeys.MEMORY_ACK_FORGET to Simple("OK, I'll forget that."),

        // ── Common (UI) ──
        StringKeys.COMMON_SAVE to Simple("Save"),
        StringKeys.COMMON_CLEAR to Simple("Clear"),
        StringKeys.COMMON_BACK to Simple("Back"),
        StringKeys.COMMON_SHOW to Simple("Show"),
        StringKeys.COMMON_HIDE to Simple("Hide"),
        StringKeys.COMMON_MASK to Simple("Mask"),
        StringKeys.COMMON_REVEAL to Simple("Reveal"),
        StringKeys.COMMON_NONE to Simple("none"),

        // ── Settings (UI) ──
        StringKeys.SETTINGS_TITLE to Simple("Settings"),
        StringKeys.SETTINGS_CONVERSATIONS_HEADER to Simple("Conversations"),
        StringKeys.SETTINGS_CONVERSATIONS_DESC to Simple(
            "Browse, resume, or delete prior chats. The most recent 50 are kept; " +
                "older conversations are removed automatically.",
        ),
        StringKeys.SETTINGS_CONVERSATIONS_MANAGE to Simple("Manage conversations"),
        StringKeys.SETTINGS_WEB_SEARCH_HEADER to Simple("Web search"),
        StringKeys.SETTINGS_WEB_SEARCH_DESC to Simple(
            "When off, the model answers from training data only.",
        ),
        StringKeys.SETTINGS_WEB_SEARCH_NO_KEY to Simple(
            "No API key — search is disabled until you add a Brave Search API key below.",
        ),
        StringKeys.SETTINGS_CACHE_HEADER to Simple("Search cache"),
        StringKeys.SETTINGS_CACHE_LOADING to Simple("…"),
        StringKeys.SETTINGS_CACHE_EMPTY to Simple("empty"),
        StringKeys.SETTINGS_CACHE_ENTRIES to Simple("%1\$d entries"),
        StringKeys.SETTINGS_CACHE_DESC to Simple(
            "Cached results: %1\$s. Time-sensitive queries expire after " +
                "5 minutes; general results after 1 hour.",
        ),
        StringKeys.SETTINGS_CACHE_CLEAR to Simple("Clear cache"),
        StringKeys.SETTINGS_CACHE_CLEARED to Simple("Cleared."),
        StringKeys.SETTINGS_SOURCES_HEADER to Simple("Search sources"),
        StringKeys.SETTINGS_SOURCES_DESC to Simple(
            "Choose what websites to use for weather, news, sports and finance questions.",
        ),
        StringKeys.SETTINGS_SOURCES_MANAGE to Simple("Manage search sources"),
        StringKeys.SETTINGS_MEMORY_HEADER to Simple("Memory"),
        StringKeys.SETTINGS_MEMORY_LOADING to Simple("…"),
        StringKeys.SETTINGS_MEMORY_NONE to Simple("no memories saved"),
        StringKeys.SETTINGS_MEMORY_ONE to Simple("1 memory saved"),
        StringKeys.SETTINGS_MEMORY_MANY to Simple("%1\$d memories saved"),
        StringKeys.SETTINGS_MEMORY_CREATION_ON to Simple("creation on"),
        StringKeys.SETTINGS_MEMORY_CREATION_OFF to Simple("creation off"),
        StringKeys.SETTINGS_MEMORY_SUMMARY to Simple("%1\$s · %2\$s."),
        StringKeys.SETTINGS_MEMORY_MANAGE to Simple("Manage memories"),
        StringKeys.SETTINGS_APPEARANCE_HEADER to Simple("Appearance"),
        StringKeys.SETTINGS_APPEARANCE_DESC to Simple(
            "Choose how the app looks. Auto follows your system's light/dark setting.",
        ),
        StringKeys.SETTINGS_APPEARANCE_LIGHT to Simple("Light"),
        StringKeys.SETTINGS_APPEARANCE_AUTO to Simple("Auto"),
        StringKeys.SETTINGS_APPEARANCE_DARK to Simple("Dark"),
        StringKeys.SETTINGS_LANGUAGE_LABEL to Simple("Language: "),
        StringKeys.SETTINGS_FONT_LABEL to Simple("Font: "),
        StringKeys.SETTINGS_FONT_SYSTEM to Simple("System default"),
        StringKeys.SETTINGS_FONT_SANS to Simple("Sans-serif"),
        StringKeys.SETTINGS_FONT_SERIF to Simple("Serif"),
        StringKeys.SETTINGS_FONT_MONOSPACE to Simple("Monospace"),
        StringKeys.SETTINGS_FONT_SIZE to Simple("Text size — %1\$d%%"),
        StringKeys.SETTINGS_FONT_PREVIEW to Simple("The quick brown fox jumps over the lazy dog."),
        StringKeys.SETTINGS_TELEMETRY_HEADER to Simple("Anonymous telemetry"),
        StringKeys.SETTINGS_TELEMETRY_ON to Simple("Aggregate counters help us improve the assistant."),
        StringKeys.SETTINGS_TELEMETRY_OFF to Simple("Off. The app never sends usage data when this is off."),
        StringKeys.SETTINGS_TELEMETRY_DETAIL to Simple(
            "What we send: counts per day — queries, search invocations, " +
                "memory operations, latency percentiles. Plus redacted " +
                "crash reports so we can fix what breaks. What we don't: " +
                "your queries, your memories, conversation content, any identifier.",
        ),
        StringKeys.SETTINGS_TELEMETRY_DESC_NOTE to Simple(
            "Off by default, and only used for application troubleshooting if needed.",
        ),
        StringKeys.SETTINGS_BRAVE_HEADER to Simple("Brave Search API"),
        StringKeys.SETTINGS_BRAVE_DESC_PRE to Simple(
            "The assistant searches the web through Brave Search. Get a key at ",
        ),
        StringKeys.SETTINGS_BRAVE_DESC_POST to Simple(" — the free tier is enough for personal use."),
        StringKeys.SETTINGS_BRAVE_DESC_PRIVACY to Simple(
            "Off by default, and sent to Brave Search API only when the Local Agent " +
                "needs current or recent information.",
        ),
        StringKeys.SETTINGS_BRAVE_STATUS_USER to Simple("Your key is set."),
        StringKeys.SETTINGS_BRAVE_STATUS_DEV to Simple(
            "No user key — using bundled dev key (debug build only).",
        ),
        StringKeys.SETTINGS_BRAVE_STATUS_NONE to Simple(
            "No key configured. Web search will be disabled until you add one.",
        ),
        StringKeys.SETTINGS_BRAVE_FIELD_LABEL to Simple("Brave API key"),
        StringKeys.SETTINGS_BRAVE_PLACEHOLDER_REPLACE to Simple("Replace existing key"),
        StringKeys.SETTINGS_BRAVE_PLACEHOLDER_PASTE to Simple("Paste key"),
        StringKeys.SETTINGS_LINK_DESKTOP_HEADER to Simple("Local Agent Connection"),
        StringKeys.SETTINGS_LINK_DESKTOP_DESC_ANYWHERE to Simple(
            "Let the Local Agent app on your phone connect to this desktop anywhere. " +
                "Scan the code below from the phone's Settings.",
        ),
        StringKeys.SETTINGS_LINK_DESKTOP_DESC_SUBSCRIBE to Simple(
            "Connect the Local Agent app on your phone to this desktop from anywhere. " +
                "Subscribe to anywhere access to show a pairing code here.",
        ),
        StringKeys.SETTINGS_LINK_DESC_SYNC to Simple(
            "Off by default, and used to synchronize the mobile and desktop agents " +
                "using a secure, end-to-end encryption with no access to the data " +
                "from any server.",
        ),
        StringKeys.SETTINGS_LINK_SUBSCRIPTION_SETTINGS to Simple("Subscription Settings"),
        StringKeys.SETTINGS_LINK_UPGRADE to Simple("Upgrade to anywhere connection"),
        StringKeys.SETTINGS_LINK_MOBILE_HEADER to Simple("Desktop Agent Connection"),
        StringKeys.SETTINGS_LINK_MOBILE_DESC to Simple(
            "Pair this phone with your desktop agent over its secure gateway subscription. " +
                "While the link is on and reachable, chat runs on the desktop and your " +
                "conversations + memories stay in sync. When it's on, the Ollama server " +
                "below is disabled.",
        ),
        StringKeys.SETTINGS_LINK_MOBILE_CONNECTED to Simple("Local Agent connected via gateway"),
        StringKeys.SETTINGS_LINK_MOBILE_OFFLINE to Simple("Mobile agent offline"),
        StringKeys.SETTINGS_LINK_MOBILE_UNPAIRED to Simple("No Local Agent paired yet"),
        StringKeys.SETTINGS_LINK_DISCONNECT to Simple("Disconnect"),
        StringKeys.SETTINGS_LINK_STATUS_OFF to Simple("Off"),
        StringKeys.SETTINGS_LINK_STATUS_NO_DESKTOP to Simple("No desktop paired"),
        StringKeys.SETTINGS_LINK_STATUS_CONNECTED to Simple("Connected to gateway"),
        StringKeys.SETTINGS_LINK_STATUS_UNREACHABLE to Simple("Gateway unreachable"),
        StringKeys.SETTINGS_OLLAMA_HEADER to Simple("Remote LLM Connection"),
        StringKeys.SETTINGS_OLLAMA_DISABLED_BY_LINK to Simple(
            "Disabled while Desktop Agent Connection is active.",
        ),
        StringKeys.SETTINGS_OLLAMA_DESC to Simple(
            "Run the chat model on a remote LLM server instead of this device. The " +
                "classifier, search and memory always stay on-device. Leave blank to use " +
                "the built-in model.",
        ),
        StringKeys.SETTINGS_OLLAMA_DESC_NOTE to Simple(
            "Off by default, and can be used to point to a dedicated LLM server either " +
                "on your local private network or in the cloud.",
        ),
        StringKeys.SETTINGS_OLLAMA_SSL_LOCKED to Simple(
            "Use SSL (https) — required for OpenAI-compatible",
        ),
        StringKeys.SETTINGS_OLLAMA_SSL_LOCKED_BY_KEY to Simple(
            "SSL is required while an API key is saved. Clear the saved key to turn it off.",
        ),
        StringKeys.SETTINGS_OLLAMA_SSL to Simple("Use SSL (https)"),
        StringKeys.SETTINGS_OLLAMA_HTTP_WARNING to Simple(
            "Traffic to this server is unencrypted (HTTP). Only use it on a trusted private " +
                "network — anyone on the network can read or alter your prompts and replies.",
        ),
        StringKeys.SETTINGS_OLLAMA_BASE_URL to Simple("Base URL"),
        StringKeys.SETTINGS_OLLAMA_HOST to Simple("Host / IP"),
        StringKeys.SETTINGS_OLLAMA_PORT to Simple("Port"),
        StringKeys.SETTINGS_OLLAMA_OPENAI_HINT to Simple(
            "Enter the full base URL ending in the API path, e.g. " +
                "https://openrouter.ai/api/v1, https://api.openai.com/v1, or " +
                "http://localhost:1234/v1 for a local server.",
        ),
        StringKeys.SETTINGS_OLLAMA_TEST to Simple("Test connection"),
        StringKeys.SETTINGS_OLLAMA_CHAT_MODEL to Simple("Chat model"),
        StringKeys.SETTINGS_OLLAMA_VISION_MODEL to Simple("Vision model (optional)"),
        StringKeys.SETTINGS_OLLAMA_APIKEY_SET to Simple(
            "API key set — sent as a Bearer token on outbound requests.",
        ),
        StringKeys.SETTINGS_OLLAMA_APIKEY_REQUIRED to Simple(
            "API key required — an OpenAI-compatible server authenticates every request.",
        ),
        StringKeys.SETTINGS_OLLAMA_APIKEY_NONE to Simple(
            "No API key — requests use the server's default (no auth). Add one only " +
                "if your server requires it.",
        ),
        StringKeys.SETTINGS_OLLAMA_APIKEY_LABEL to Simple("API key"),
        StringKeys.SETTINGS_OLLAMA_APIKEY_LABEL_OPTIONAL to Simple("API key (optional)"),
        StringKeys.SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_REPLACE to Simple("Replace existing key"),
        StringKeys.SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_PASTE to Simple("Paste key"),
        StringKeys.SETTINGS_OLLAMA_APIKEY_REQUIRES_SSL to Simple(
            "Enable \"Use SSL (https)\" to save an API key — keys are never sent over " +
                "cleartext HTTP.",
        ),
        StringKeys.SETTINGS_OLLAMA_SAVE_KEY to Simple("Save key"),
        StringKeys.SETTINGS_OLLAMA_CLEAR_KEY to Simple("Clear key"),
        StringKeys.SETTINGS_OLLAMA_STATUS_CONNECTING to Simple("Connecting…"),
        StringKeys.SETTINGS_OLLAMA_STATUS_CONNECTED to Simple("Connected — %1\$d models found."),
        StringKeys.SETTINGS_OLLAMA_STATUS_NO_MODELS_OPENAI to Simple(
            "Reached the server, but it returned no models. Check the Base URL includes the " +
                "full API path (e.g. it should end in /v1 or /api/v1).",
        ),
        StringKeys.SETTINGS_OLLAMA_STATUS_NO_MODELS to Simple(
            "Reached the server, but it has no models installed (try `ollama pull <model>`).",
        ),
        StringKeys.SETTINGS_OLLAMA_STATUS_FAILED to Simple(
            "Could not reach the server. Check the host, port, and that the server is running.",
        ),
        StringKeys.SETTINGS_OLLAMA_STATUS_OFF to Simple(
            "Switched off — chat uses the on-device model (server details kept).",
        ),
        StringKeys.SETTINGS_OLLAMA_STATUS_ACTIVE to Simple(
            "Using remote LLM at %1\$s (%2\$s) — on-device model disabled.",
        ),
        StringKeys.SETTINGS_OLLAMA_STATUS_NOT_CONFIGURED to Simple(
            "Not configured — chat uses the on-device model.",
        ),
        StringKeys.SETTINGS_OLLAMA_SERVER_TYPE_LABEL to Simple("Server type: "),
        StringKeys.SETTINGS_ABOUT_HEADER to Simple("About"),
        StringKeys.SETTINGS_ABOUT_DESKTOP_AGENT to Simple("Desktop Agent"),
        StringKeys.SETTINGS_ABOUT_LOCAL_AGENT to Simple("Local Agent"),
        StringKeys.SETTINGS_ABOUT_BUILD_INFO to Simple("Version %1\$s\nBuild %2\$s\nGit %3\$s"),

        // ── Chat ──
        StringKeys.CHAT_EMPTY_TITLE to Simple("Hello."),
        StringKeys.CHAT_EMPTY_BODY to Simple(
            "The Local Agent runs on your phone or computer - your conversations " +
                "stay confidential and private.",
        ),
        StringKeys.CHAT_INPUT_HINT to Simple("Ask anything…"),
        StringKeys.CHAT_SEND to Simple("Send"),
        StringKeys.CHAT_CANCEL to Simple("Cancel"),
        StringKeys.CHAT_CANCELLING to Simple("Cancelling…"),
        StringKeys.CHAT_DISMISS to Simple("Dismiss"),
        StringKeys.CHAT_OK to Simple("OK"),
        StringKeys.CHAT_ERROR_PREFIX to Simple("Error: %1\$s"),
        StringKeys.CHAT_THINKING to Simple("Thinking…"),
        StringKeys.CHAT_FROM_CACHE to Simple("From cache"),
        StringKeys.CHAT_SEARCHING to Simple("Searching: %1\$s"),
        StringKeys.CHAT_RUNNING_JOB to Simple("Running job: %1\$s…"),
        StringKeys.CHAT_SEARCH_FAILED to Simple("Search %1\$s: %2\$s"),
        StringKeys.CHAT_OVERFLOW_TITLE to Simple("Conversation limit reached"),
        StringKeys.CHAT_OVERFLOW_BODY to Simple(
            "This conversation has reached the maximum context length. " +
                "Continue to send your message — the oldest message pair " +
                "will be permanently removed — or start a new conversation.\n\n" +
                "Your message:\n\"%1\$s\"",
        ),
        StringKeys.CHAT_OVERFLOW_CONTINUE to Simple("Continue"),
        StringKeys.CHAT_OVERFLOW_START_NEW to Simple("Start new conversation"),
        StringKeys.CHAT_MEMORY_LIMIT_TITLE to Simple("Memory limit reached"),
        StringKeys.CHAT_MEMORY_LIMIT_BODY to Simple(
            "You've saved the maximum of %1\$d memories. " +
                "Delete some in Settings → Memory to save new ones.",
        ),
        StringKeys.CHAT_MEMORY_PROMPT_SAVE_AS to Simple("Save this as a %1\$s?"),
        StringKeys.CHAT_CATEGORY_PERSONAL_IDENTITY to Simple("personal detail"),
        StringKeys.CHAT_CATEGORY_PREFERENCE to Simple("preference"),
        StringKeys.CHAT_CATEGORY_PROFESSIONAL to Simple("professional detail"),
        StringKeys.CHAT_CATEGORY_INTEREST to Simple("interest"),
        StringKeys.CHAT_CATEGORY_RELATIONSHIP to Simple("relationship"),
        StringKeys.CHAT_CATEGORY_TEMPORARY_CONTEXT to Simple("temporary note"),
        StringKeys.CHAT_SESSION_UNLOADED to Simple("Model unloaded — next prompt cold-loads in 4–8 s."),
        StringKeys.CHAT_SESSION_DOWNLOADING to Simple("Downloading model files…%1\$s"),
        StringKeys.CHAT_SESSION_LOADING to Simple("Loading model…"),
        StringKeys.CHAT_SESSION_LOADED_CPU to Simple(
            "Loaded on CPU (degraded mode — generation will be slow).",
        ),
        StringKeys.CHAT_SESSION_LOADED to Simple("Loaded on %1\$s."),
        StringKeys.CHAT_SESSION_FAILED to Simple("Model load failed: %1\$s"),
        StringKeys.CHAT_SESSION_MOBILE_UNLOADED to Simple("Model unloaded"),
        StringKeys.CHAT_SESSION_MOBILE_DOWNLOADING to Simple("Model downloading%1\$s"),
        StringKeys.CHAT_SESSION_MOBILE_LOADING to Simple("Model loading"),
        StringKeys.CHAT_SESSION_MOBILE_LOADED_GPU to Simple("Model loaded on GPU"),
        StringKeys.CHAT_SESSION_MOBILE_LOADED_CPU to Simple("Model loaded on CPU"),
        StringKeys.CHAT_THERMAL_WARM to Simple("Your device is running warm. Responses may be slower."),
        StringKeys.CHAT_THERMAL_BLOCK_TITLE to Simple("Device too hot for generation"),
        StringKeys.CHAT_THERMAL_BLOCK_BODY to Simple(
            "Wait a few minutes for the device to cool. Send is " +
                "disabled until the thermal state clears.",
        ),
        StringKeys.CHAT_CD_NEW_CHAT to Simple("New chat"),
        StringKeys.CHAT_CD_MYLIST to Simple("My List (%1\$d open)"),
        StringKeys.CHAT_CD_TIMERS to Simple("Timers (%1\$d active)"),
        StringKeys.CHAT_CD_ALARMS to Simple("Alarms (%1\$d active)"),
        StringKeys.CHAT_CD_JOBS to Simple("Jobs"),
        StringKeys.CHAT_CD_SETTINGS to Simple("Settings"),
        StringKeys.CHAT_CD_JUMP_TO_LATEST to Simple("Jump to latest"),
        StringKeys.CHAT_CD_ATTACHED_IMAGE to Simple("Attached image"),
        StringKeys.CHAT_CD_ATTACH_IMAGE to Simple("Attach image"),
        StringKeys.CHAT_CD_REMOVE_IMAGE to Simple("Remove image"),
        StringKeys.CHAT_CD_CLEAR_INPUT to Simple("Clear text"),
        StringKeys.CHAT_CD_DELETE_TURN to Simple("Delete this exchange"),
        StringKeys.CHAT_DELETE_TURN_TITLE to Simple("Delete this exchange?"),
        StringKeys.CHAT_DELETE_TURN_BODY to Simple("This removes your message and the response from the conversation."),
        StringKeys.CHAT_DELETE_TURN_CONFIRM to Simple("Delete"),
        StringKeys.CHAT_DELETE_TURN_CANCEL to Simple("Cancel"),
        StringKeys.CHAT_CD_MIC_START to Simple("Start dictation"),
        StringKeys.CHAT_CD_MIC_STOP to Simple("Stop dictation"),
        StringKeys.CHAT_CD_TTS_ENABLE to Simple("Enable read-aloud"),
        StringKeys.CHAT_CD_TTS_DISABLE to Simple("Disable read-aloud"),
        StringKeys.CHAT_CD_DISMISS_THERMAL to Simple("Dismiss thermal warning"),
        StringKeys.CHAT_CD_MEM_HEALTHY to Simple("System memory: healthy"),
        StringKeys.CHAT_CD_MEM_CAUTION to Simple("System memory: caution"),
        StringKeys.CHAT_CD_MEM_LOW to Simple("System memory: low"),
        StringKeys.CHAT_CD_LINK_CONNECTED to Simple("Desktop link: connected"),
        StringKeys.CHAT_CD_LINK_UNREACHABLE to Simple("Desktop link: unreachable"),

        // ── Onboarding ──
        StringKeys.ONBOARDING_NAV_CONTINUE to Simple("Continue"),
        StringKeys.ONBOARDING_NAV_SKIP_SETTINGS to Simple("Skip — I'll add it later in Settings"),
        StringKeys.ONBOARDING_LANGUAGE_TITLE to Simple("Choose your language"),
        StringKeys.ONBOARDING_LANGUAGE_BODY to Simple(
            "Select the language for the app and the assistant's replies. " +
                "You can change it anytime in Settings.",
        ),

        // ── Onboarding — model download ──
        StringKeys.DOWNLOAD_TITLE to Simple("Set up the on-device models"),
        StringKeys.DOWNLOAD_INTRO to Simple(
            "One-time download of the AI models for multi-modal question " +
                "answering, summarization, reasoning, search filters and " +
                "saving memories.",
        ),
        StringKeys.DOWNLOAD_MODELS_HEADER to Simple("Models to download:"),
        StringKeys.DOWNLOAD_TOTAL to Simple("Total download: %1\$s"),
        StringKeys.DOWNLOAD_SPEC_INCOMPLETE to Simple(
            "⚠ Model spec is incomplete. The model coordinates (URL + sha256 + size) " +
                "are pinned in ModelInventory; this usually means a bad build.",
        ),
        StringKeys.DOWNLOAD_STATE_IDLE to Simple("Ready to download."),
        StringKeys.DOWNLOAD_STATE_QUEUED to Simple("Queued — waiting for network or retry backoff…"),
        StringKeys.DOWNLOAD_STATE_STARTING to Simple("Starting…"),
        StringKeys.DOWNLOAD_STATE_COMPLETED to Simple("✓ Model is ready."),
        // %1$s = percent, %2$s = downloaded size, %3$s = total size.
        StringKeys.DOWNLOAD_PROGRESS to Simple("%1\$s%% — %2\$s of %3\$s"),
        StringKeys.DOWNLOAD_FAILED to Simple("Download failed: %1\$s"),
        StringKeys.DOWNLOAD_ACTION_WIFI to Simple("Download (WiFi only)"),
        StringKeys.DOWNLOAD_ACTION_CELLULAR to Simple("Allow cellular"),
        StringKeys.DOWNLOAD_ACTION_PAUSE to Simple("Pause"),
        StringKeys.DOWNLOAD_ACTION_RETRY_WIFI to Simple("Retry (WiFi)"),
        StringKeys.DOWNLOAD_ACTION_RETRY_CELLULAR to Simple("Retry (allow cellular)"),
        StringKeys.DOWNLOAD_ERROR_NETWORK to Simple("Network error — check your connection."),
        StringKeys.DOWNLOAD_ERROR_HTTP_CLIENT to Simple("Server rejected the request. Please try again later."),
        StringKeys.DOWNLOAD_ERROR_HTTP_SERVER to Simple("Server problem — please try again later."),
        StringKeys.DOWNLOAD_ERROR_STORAGE to Simple("Not enough free storage."),
        StringKeys.DOWNLOAD_ERROR_CHECKSUM to Simple("The file didn't match its expected checksum and was discarded."),
        StringKeys.DOWNLOAD_ERROR_MISCONFIGURED to Simple("Model spec missing — see settings."),

        // ── Memory ──
        StringKeys.MEMORY_TITLE to Simple("Memory"),
        StringKeys.MEMORY_CONVERSATION_TITLE to Simple("Memories from this chat"),
        StringKeys.MEMORY_EMPTY to Simple(
            "No memories saved yet. They'll appear here as the assistant learns about you.",
        ),
        StringKeys.MEMORY_CONVERSATION_EMPTY to Simple("No memories from this conversation yet."),
        StringKeys.MEMORY_LOADING to Simple("Loading…"),
        StringKeys.MEMORY_CREATION_TOGGLE to Simple("Remember things from our conversations"),
        StringKeys.MEMORY_CLEAR_ALL to Simple("Clear all"),
        StringKeys.MEMORY_CLEAR_ALL_TITLE to Simple("Clear all memories?"),
        StringKeys.MEMORY_CLEAR_ALL_BODY to Simple(
            "All %1\$d memories will be permanently deleted. This cannot be undone.",
        ),
        StringKeys.MEMORY_EXPORT to Simple("Export…"),
        StringKeys.MEMORY_IMPORT to Simple("Import…"),
        StringKeys.MEMORY_TOAST_NOTHING_EXPORT to Simple("Nothing to export."),
        StringKeys.MEMORY_TOAST_EXPORTED to Plural(
            mapOf("one" to "Exported %1\$d memory.", "other" to "Exported %1\$d memories."),
        ),
        StringKeys.MEMORY_TOAST_IMPORTED to Plural(
            mapOf("one" to "Imported %1\$d memory.", "other" to "Imported %1\$d memories."),
        ),
        StringKeys.MEMORY_TOAST_IMPORTED_SKIPPED to Plural(
            mapOf(
                "one" to "Imported %1\$d; skipped %2\$d invalid row.",
                "other" to "Imported %1\$d; skipped %2\$d invalid rows.",
            ),
        ),
        StringKeys.MEMORY_DELETE_TITLE to Simple("Delete memory?"),
        StringKeys.MEMORY_DELETE_BODY to Simple("\"%1\$s\"\n\nThis cannot be undone."),
        StringKeys.MEMORY_DELETE to Simple("Delete"),
        StringKeys.MEMORY_CANCEL to Simple("Cancel"),
        StringKeys.MEMORY_OK to Simple("OK"),
        StringKeys.MEMORY_IMPORT_TITLE to Simple("Replace all memories?"),
        StringKeys.MEMORY_IMPORT_BODY to Plural(
            mapOf(
                "one" to "Importing will erase your current %1\$d memory and replace " +
                    "them with the contents of the chosen file. This cannot be undone.",
                "other" to "Importing will erase your current %1\$d memories and replace " +
                    "them with the contents of the chosen file. This cannot be undone.",
            ),
        ),
        StringKeys.MEMORY_CHOOSE_FILE to Simple("Choose file"),
        StringKeys.MEMORY_IMPORT_CAP_TITLE to Simple("Too many memories to import"),
        StringKeys.MEMORY_IMPORT_CAP_BODY to Simple(
            "This file has %1\$d memories, more than the maximum of %2\$d. Nothing was " +
                "imported and your current memories were kept. Reduce the file to %2\$d or " +
                "fewer memories and try again.",
        ),
        StringKeys.MEMORY_CREATED to Simple("Created %1\$s%2\$s"),
        StringKeys.MEMORY_CREATED_EXPIRES to Simple(" · expires %1\$s"),
        StringKeys.MEMORY_CD_MORE to Simple("More"),
        StringKeys.MEMORY_CD_DELETE to Simple("Delete memory"),
        StringKeys.MEMORY_CATEGORY_PERSONAL_IDENTITY to Simple("Personal identity"),
        StringKeys.MEMORY_CATEGORY_PREFERENCE to Simple("Preferences"),
        StringKeys.MEMORY_CATEGORY_PROFESSIONAL to Simple("Professional"),
        StringKeys.MEMORY_CATEGORY_INTEREST to Simple("Interests"),
        StringKeys.MEMORY_CATEGORY_RELATIONSHIP to Simple("Relationships"),
        StringKeys.MEMORY_CATEGORY_TEMPORARY_CONTEXT to Simple("Temporary"),
        StringKeys.MEMORY_CATEGORY_SHORT_PERSONAL_IDENTITY to Simple("identity"),
        StringKeys.MEMORY_CATEGORY_SHORT_PREFERENCE to Simple("preference"),
        StringKeys.MEMORY_CATEGORY_SHORT_PROFESSIONAL to Simple("professional"),
        StringKeys.MEMORY_CATEGORY_SHORT_INTEREST to Simple("interest"),
        StringKeys.MEMORY_CATEGORY_SHORT_RELATIONSHIP to Simple("relationship"),
        StringKeys.MEMORY_CATEGORY_SHORT_TEMPORARY_CONTEXT to Simple("temporary"),

        // ── Clock (UI) ──
        StringKeys.CLOCK_UI_ALARMS_TITLE to Simple("Alarms"),
        StringKeys.CLOCK_UI_ALARMS_EMPTY to Simple("No alarms scheduled."),
        StringKeys.CLOCK_UI_ALARMS_EMPTY_HINT to Simple(
            "Tap + to add one, or ask in chat — try \"set an alarm for 7am\".",
        ),
        StringKeys.CLOCK_UI_TIMERS_TITLE to Simple("Timers"),
        StringKeys.CLOCK_UI_TIMERS_EMPTY to Simple("No active timers."),
        StringKeys.CLOCK_UI_TIMERS_EMPTY_HINT to Simple(
            "Tap + to start one, or ask in chat — try \"set a timer for 5 minutes\".",
        ),
        StringKeys.CLOCK_UI_ONCE to Simple("Once"),
        StringKeys.CLOCK_UI_WEEKDAYS to Simple("Weekdays"),
        StringKeys.CLOCK_UI_WEEKENDS to Simple("Weekends"),
        StringKeys.CLOCK_UI_EVERY_DAY to Simple("Every day"),
        StringKeys.CLOCK_UI_OFF_SUFFIX to Simple(" · off"),
        StringKeys.CLOCK_UI_EDIT to Simple("Edit"),
        StringKeys.CLOCK_UI_CANCEL to Simple("Cancel"),
        StringKeys.CLOCK_UI_ADD to Simple("Add"),
        StringKeys.CLOCK_UI_NEW_ALARM to Simple("New alarm"),
        StringKeys.CLOCK_UI_EDIT_ALARM to Simple("Edit alarm"),
        StringKeys.CLOCK_UI_LABEL_OPTIONAL to Simple("Label (optional)"),
        StringKeys.CLOCK_UI_NEW_TIMER to Simple("New timer"),
        StringKeys.CLOCK_UI_TIMER_DEFAULT_NAME to Simple("Timer"),
        StringKeys.CLOCK_UI_EXTEND_1MIN to Simple("+1 min"),
        StringKeys.CLOCK_UI_EXTEND_5MIN to Simple("+5 min"),
        StringKeys.CLOCK_UI_DURATION_H to Simple("h"),
        StringKeys.CLOCK_UI_DURATION_M to Simple("m"),
        StringKeys.CLOCK_UI_DURATION_S to Simple("s"),
        StringKeys.CLOCK_UI_START to Simple("Start"),
        StringKeys.CLOCK_UI_CD_NEW_ALARM to Simple("New alarm"),
        StringKeys.CLOCK_UI_CD_NEW_TIMER to Simple("New timer"),
        StringKeys.CLOCK_UI_DAY_LETTER_SUN to Simple("S"),
        StringKeys.CLOCK_UI_DAY_LETTER_MON to Simple("M"),
        StringKeys.CLOCK_UI_DAY_LETTER_TUE to Simple("T"),
        StringKeys.CLOCK_UI_DAY_LETTER_WED to Simple("W"),
        StringKeys.CLOCK_UI_DAY_LETTER_THU to Simple("T"),
        StringKeys.CLOCK_UI_DAY_LETTER_FRI to Simple("F"),
        StringKeys.CLOCK_UI_DAY_LETTER_SAT to Simple("S"),
        StringKeys.CLOCK_UI_DAY_SHORT_MON to Simple("Mon"),
        StringKeys.CLOCK_UI_DAY_SHORT_TUE to Simple("Tue"),
        StringKeys.CLOCK_UI_DAY_SHORT_WED to Simple("Wed"),
        StringKeys.CLOCK_UI_DAY_SHORT_THU to Simple("Thu"),
        StringKeys.CLOCK_UI_DAY_SHORT_FRI to Simple("Fri"),
        StringKeys.CLOCK_UI_DAY_SHORT_SAT to Simple("Sat"),
        StringKeys.CLOCK_UI_DAY_SHORT_SUN to Simple("Sun"),

        // ── My List (UI) ──
        StringKeys.MYLIST_UI_TITLE to Simple("My List"),
        StringKeys.MYLIST_UI_EMPTY to Simple("Your list is empty."),
        StringKeys.MYLIST_UI_EMPTY_HINT to Simple(
            "Tap + to add one, or ask in chat — try \"add buy milk to my list\".",
        ),
        StringKeys.MYLIST_UI_CLEAR_DONE to Simple("Clear done"),
        StringKeys.MYLIST_UI_DELETE_TITLE to Simple("Delete item?"),
        StringKeys.MYLIST_UI_DELETE_BODY to Simple("\"%1\$s\" will be permanently removed."),
        StringKeys.MYLIST_UI_DELETE to Simple("Delete"),
        StringKeys.MYLIST_UI_CANCEL to Simple("Cancel"),
        StringKeys.MYLIST_UI_NEW_ITEM to Simple("New item"),
        StringKeys.MYLIST_UI_EDIT_ITEM to Simple("Edit item"),
        StringKeys.MYLIST_UI_TITLE_LABEL to Simple("Title"),
        StringKeys.MYLIST_UI_PRIORITY to Simple("Priority"),
        StringKeys.MYLIST_UI_DUE_PREFIX to Simple("Due: "),
        StringKeys.MYLIST_UI_SET to Simple("Set"),
        StringKeys.MYLIST_UI_CHANGE to Simple("Change"),
        StringKeys.MYLIST_UI_NOTES_OPTIONAL to Simple("Notes (optional)"),
        StringKeys.MYLIST_UI_ADD to Simple("Add"),
        StringKeys.MYLIST_UI_OK to Simple("OK"),
        StringKeys.MYLIST_UI_CD_ADD to Simple("Add item"),
        StringKeys.MYLIST_UI_CD_EDIT to Simple("Edit"),
        StringKeys.MYLIST_UI_CD_DELETE to Simple("Delete"),

        // ── History ──
        StringKeys.HISTORY_TITLE to Simple("Conversation history"),
        StringKeys.HISTORY_EMPTY to Simple("No conversations yet."),
        StringKeys.HISTORY_CAPACITY_FOOTNOTE to Simple(
            "Stores up to %1\$d conversations. Oldest are removed automatically.",
        ),
        StringKeys.HISTORY_DELETE_TITLE to Simple("Delete conversation?"),
        StringKeys.HISTORY_DELETE_BODY to Simple(
            "\"%1\$s\"\n\nThis deletes the conversation and its messages. Memories " +
                "saved from this chat are kept.",
        ),
        StringKeys.HISTORY_DELETE to Simple("Delete"),
        StringKeys.HISTORY_CANCEL to Simple("Cancel"),
        StringKeys.HISTORY_CD_DELETE to Simple("Delete conversation"),
        StringKeys.HISTORY_SEARCH_HINT to Simple("Search conversations"),
        StringKeys.HISTORY_CD_SEARCH to Simple("Search"),
        StringKeys.HISTORY_CD_CLOSE_SEARCH to Simple("Close search"),
        StringKeys.HISTORY_CD_CLEAR_SEARCH to Simple("Clear"),
        StringKeys.HISTORY_SEARCH_EMPTY to Simple("No conversations match your search."),

        // ── Jobs (UI) ──
        StringKeys.JOBS_TITLE to Simple("Jobs"),
        StringKeys.JOBS_CD_ADD to Simple("Add job"),
        StringKeys.JOBS_DELETE_TITLE to Simple("Delete job?"),
        StringKeys.JOBS_DELETE_BODY to Simple("\"%1\$s\" will be removed and will no longer run."),
        StringKeys.JOBS_DELETE to Simple("Delete"),
        StringKeys.JOBS_CANCEL to Simple("Cancel"),
        StringKeys.JOBS_SYNCED to Simple("Synced %1\$s"),
        StringKeys.JOBS_NEVER_SYNCED to Simple("Never synced"),
        StringKeys.JOBS_STATUS_ONLINE to Simple("Online"),
        StringKeys.JOBS_STATUS_OFFLINE to Simple("Offline"),
        StringKeys.JOBS_STATUS_NOT_LINKED to Simple("Not linked"),
        StringKeys.JOBS_EMPTY to Simple("No jobs yet."),
        StringKeys.JOBS_EMPTY_HINT_ADMIN to Simple("Tap + to schedule a command."),
        StringKeys.JOBS_EMPTY_HINT_REMOTE to Simple("Jobs are created on the desktop agent."),
        StringKeys.JOBS_RUNNING to Simple("Running…"),
        StringKeys.JOBS_STATUS_RUNNING to Simple("Running"),
        StringKeys.JOBS_STATUS_SUCCEEDED to Simple("Succeeded"),
        StringKeys.JOBS_STATUS_FAILED to Simple("Failed"),
        StringKeys.JOBS_STATUS_CANCELLED to Simple("Cancelled"),
        StringKeys.JOBS_VIEW_CONVERSATION to Simple("View conversation"),
        StringKeys.JOBS_CD_RUN_NOW to Simple("Run now"),
        StringKeys.JOBS_CD_CANCEL_RUN to Simple("Cancel run"),
        StringKeys.JOBS_CD_EDIT to Simple("Edit"),
        StringKeys.JOBS_CD_DELETE to Simple("Delete"),
        StringKeys.JOBS_SCHED_DAILY_AT to Simple("Daily at %1\$s"),
        StringKeys.JOBS_SCHED_AT to Simple("%1\$s at %2\$s"),
        StringKeys.JOBS_SCHED_CRON_RAW to Simple("cron: %1\$s"),
        StringKeys.JOBS_REPEAT to Simple("Repeat"),
        StringKeys.JOBS_SCHED_ONCE_ON to Simple("Once on %1\$s"),
        StringKeys.JOBS_ONCE to Simple("Once"),
        StringKeys.JOBS_DAY_SHORT_SUN to Simple("Sun"),
        StringKeys.JOBS_DAY_SHORT_MON to Simple("Mon"),
        StringKeys.JOBS_DAY_SHORT_TUE to Simple("Tue"),
        StringKeys.JOBS_DAY_SHORT_WED to Simple("Wed"),
        StringKeys.JOBS_DAY_SHORT_THU to Simple("Thu"),
        StringKeys.JOBS_DAY_SHORT_FRI to Simple("Fri"),
        StringKeys.JOBS_DAY_SHORT_SAT to Simple("Sat"),
        StringKeys.JOBS_DAY_LETTER_SUN to Simple("S"),
        StringKeys.JOBS_DAY_LETTER_MON to Simple("M"),
        StringKeys.JOBS_DAY_LETTER_TUE to Simple("T"),
        StringKeys.JOBS_DAY_LETTER_WED to Simple("W"),
        StringKeys.JOBS_DAY_LETTER_THU to Simple("T"),
        StringKeys.JOBS_DAY_LETTER_FRI to Simple("F"),
        StringKeys.JOBS_DAY_LETTER_SAT to Simple("S"),
        StringKeys.JOBS_FORM_NEW to Simple("New job"),
        StringKeys.JOBS_FORM_EDIT to Simple("Edit job"),
        StringKeys.JOBS_FORM_CREATE to Simple("Create"),
        StringKeys.JOBS_FORM_NAME to Simple("Job Name"),
        StringKeys.JOBS_FORM_LOCATION to Simple("Job Command"),
        StringKeys.JOBS_FORM_CHOOSE_TOOLTIP to Simple(
            "Pick a ready-made job from the catalog.",
        ),
        StringKeys.JOBS_FORM_CHOOSE_JOB to Simple("Choose job…"),
        StringKeys.JOBS_FORM_KEYWORDS to Simple("Keyword(s)"),
        StringKeys.JOBS_FORM_SCHEDULE to Simple("Schedule"),
        StringKeys.JOBS_FORM_AM to Simple("AM"),
        StringKeys.JOBS_FORM_PM to Simple("PM"),
        StringKeys.JOBS_FORM_HOUR to Simple("Hour"),
        StringKeys.JOBS_FORM_MIN to Simple("Min"),
        StringKeys.JOBS_FORM_RUNS_DAILY_AT to Simple("Runs daily at %1\$s"),
        StringKeys.JOBS_FORM_RUNS_DAYS_AT to Simple("Runs %1\$s at %2\$s"),
        StringKeys.JOBS_FORM_RUN_ONCE_AFTER to Simple("Run once after"),
        StringKeys.JOBS_FORM_DUR_H to Simple("h"),
        StringKeys.JOBS_FORM_DUR_M to Simple("m"),
        StringKeys.JOBS_FORM_DUR_S to Simple("s"),
        StringKeys.JOBS_CHOOSE_TITLE to Simple("Choose a job"),
        StringKeys.JOBS_CHOOSE_LOADING to Simple("Loading jobs…"),
        StringKeys.JOBS_CHOOSE_EMPTY to Simple("No jobs available."),
        StringKeys.JOBS_CHOOSE_UNAVAILABLE_OS to Simple("Not available on this computer"),
        StringKeys.JOBS_INIT_SETTING_UP to Simple("Setting up…"),
        StringKeys.JOBS_INIT_AWAIT_USER to Simple("Action needed"),
        StringKeys.JOBS_INIT_FAILED_TITLE to Simple("Setup didn't finish"),
        StringKeys.JOBS_INIT_INTRO to Simple("This job needs a one-time setup:"),
        StringKeys.JOBS_INIT_NONE to Simple("No setup needed."),
        StringKeys.JOBS_INIT_APPROVE to Simple("Approve"),

        // ── Search sources (UI) ──
        StringKeys.SEARCH_SOURCES_TITLE to Simple("Search sources"),
        StringKeys.SEARCH_SOURCES_COUNTRY_LABEL to Simple("Default country"),
        StringKeys.SEARCH_SOURCES_GENERAL to Simple("General search"),
        StringKeys.SEARCH_SOURCES_NEWS to Simple("News"),
        StringKeys.SEARCH_SOURCES_WEATHER to Simple("Weather"),
        StringKeys.SEARCH_SOURCES_SPORTS to Simple("Sports"),
        StringKeys.SEARCH_SOURCES_FINANCE to Simple("Finance"),
        StringKeys.SEARCH_SOURCES_NONE_CONFIGURED to Simple("No sources configured."),
        StringKeys.SEARCH_SOURCES_ADD to Simple("Add source"),
        StringKeys.SEARCH_SOURCES_CD_EDIT to Simple("Edit %1\$s"),
        StringKeys.SEARCH_SOURCES_CD_REMOVE to Simple("Remove %1\$s"),
        StringKeys.SEARCH_SOURCES_DIALOG_TITLE_ADD to Simple("Add %1\$s source"),
        StringKeys.SEARCH_SOURCES_DIALOG_TITLE_EDIT to Simple("Edit %1\$s source"),
        StringKeys.SEARCH_SOURCES_DIALOG_ADD to Simple("Add"),
        StringKeys.SEARCH_SOURCES_DIALOG_CANCEL to Simple("Cancel"),
        StringKeys.SEARCH_SOURCES_DIALOG_DOMAIN to Simple("Domain (e.g. cbc.ca)"),
        StringKeys.SEARCH_SOURCES_DIALOG_DISPLAY_NAME to Simple("Display name (optional)"),
        StringKeys.SEARCH_SOURCES_DIALOG_KIND to Simple("Kind: %1\$s"),
        StringKeys.SEARCH_SOURCES_DIALOG_ENDPOINT to Simple("Endpoint URL or template"),
        StringKeys.SEARCH_SOURCES_DIALOG_TEMPLATE_HINT to Simple(
            "Templates support {country}, {region}, {city}, {query}.",
        ),

        // ── Relative-time / date formatting ──
        StringKeys.FMT_NOW to Simple("now"),
        StringKeys.FMT_AGO to Simple("%1\$s ago"),
        StringKeys.FMT_IN to Simple("in %1\$s"),
        StringKeys.FMT_MONTHS to Listed(
            listOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            ),
        ),
        StringKeys.FMT_DATE_SHORT to Simple("%1\$s %2\$d"),
        StringKeys.FMT_DATE_YEAR to Simple("%1\$s %2\$d, %3\$d"),

        // ── Notifications (titles; bodies stay as data) ──
        StringKeys.NOTIF_JOB_FINISHED to Simple("Job finished"),
        StringKeys.NOTIF_JOB_FAILED to Simple("Job failed"),
        StringKeys.NOTIF_TASK_COMPLETE to Simple("Task complete"),
        StringKeys.NOTIF_TASK_CANCELLED to Simple("Task cancelled"),
        StringKeys.NOTIF_TASK_FAILED to Simple("Task failed"),
        StringKeys.NOTIF_MODEL_DOWNLOADING to Simple("Downloading model"),
        StringKeys.NOTIF_MODEL_STARTING to Simple("Starting…"),
        StringKeys.NOTIF_MODEL_READY to Simple("Model ready"),
        StringKeys.NOTIF_MODEL_COMPLETE to Simple("Download complete."),
        StringKeys.NOTIF_MODEL_FAILED to Simple("Model download failed"),
        StringKeys.NOTIF_MODELS_READY to Simple("Models ready"),
        StringKeys.NOTIF_MODELS_ALL_DONE to Simple("All models downloaded."),
        StringKeys.NOTIF_MODELS_INCOMPLETE to Simple("Model download incomplete"),
        StringKeys.NOTIF_MODELS_FAILED_COUNT to Plural(
            mapOf("one" to "%1\$d download failed.", "other" to "%1\$d downloads failed."),
        ),

        // ── Data-display names (country names) ──
        StringKeys.DATA_COUNTRY_CA to Simple("Canada"),
        StringKeys.DATA_COUNTRY_US to Simple("United States"),
        StringKeys.DATA_COUNTRY_GB to Simple("United Kingdom"),
        StringKeys.DATA_COUNTRY_AU to Simple("Australia"),

        // ── Desktop sections (UI) ──
        StringKeys.DESKTOP_VOICE_SECTION_HEADER to Simple("Read-aloud voice"),
        StringKeys.DESKTOP_VOICE_DESCRIPTION to Simple(
            "Pick the voice used when read-aloud is on. Piper is a high-quality neural voice " +
                "downloaded the first time you use it (~90 MB) and runs fully offline. The other " +
                "options come from your system's speech engine.",
        ),
        StringKeys.DESKTOP_VOICE_ENGINE_PIPER to Simple("Piper — neural (recommended)"),
        StringKeys.DESKTOP_VOICE_SYSTEM_DEFAULT to Simple("System default"),
        StringKeys.DESKTOP_VOICE_ENGINE_LABEL to Simple("Engine"),
        StringKeys.DESKTOP_VOICE_VOICE_LABEL to Simple("Voice"),
        StringKeys.DESKTOP_VOICE_SPEECH_RATE to Simple("Speech rate — %1\$s"),
        StringKeys.DESKTOP_VOICE_RATE_NORMAL to Simple("normal"),
        StringKeys.DESKTOP_VOICE_RATE_SLOWER to Simple("slower (%1\$d)"),
        StringKeys.DESKTOP_VOICE_RATE_FASTER to Simple("faster (+%1\$d)"),
        StringKeys.DESKTOP_VOICE_TEST_VOICE to Simple("Test voice"),
        StringKeys.DESKTOP_VOICE_TEST_UTTERANCE to Simple("This is the read-aloud voice."),
        StringKeys.DESKTOP_VOICE_DOWNLOADING to Simple("Downloading neural voice… %1\$d%%"),
        StringKeys.DESKTOP_VOICE_READY to Simple("Neural voice ready."),
        StringKeys.DESKTOP_VOICE_FAILED to Simple("%1\$s It will retry on the next read-aloud."),
        StringKeys.DESKTOP_VOICE_UNAVAILABLE to Simple("Not available on this platform."),
        StringKeys.DESKTOP_VOICE_IDLE to Simple("Downloads ~90 MB the first time it speaks."),
        StringKeys.DESKTOP_VOICE_FILTER to Simple("Filter"),
        StringKeys.DESKTOP_VOICE_SHOWING to Simple("Showing %1\$d of %2\$d — type to filter."),
        StringKeys.DESKTOP_GPU_TITLE to Simple("GPU device"),
        StringKeys.DESKTOP_GPU_DESCRIPTION to Simple(
            "On a machine with more than one GPU, pin the local model to a single device so it " +
                "doesn't fall back to a slower integrated GPU. Click “Detect devices” to see " +
                "what's available (downloads the GPU runtime the first time).",
        ),
        StringKeys.DESKTOP_GPU_DETECT_DEVICES to Simple("Detect devices"),
        StringKeys.DESKTOP_GPU_AUTO_ALL to Simple("Auto — all GPUs"),
        StringKeys.DESKTOP_GPU_DEVICE_PREFIX to Simple("Device: "),
        StringKeys.DESKTOP_GPU_PINNED to Simple("pinned"),
        StringKeys.DESKTOP_GPU_DETECTION_FAILED to Simple("Detection failed"),
        StringKeys.DESKTOP_GPU_NO_DEVICES to Simple("No devices detected yet."),
        StringKeys.DESKTOP_LINK_SUBSCRIBE_PROMPT to
            Simple("Subscribe to anywhere access to show a pairing code for your phone."),
        StringKeys.DESKTOP_LINK_QR_CD to Simple("Desktop pairing QR"),
        StringKeys.DESKTOP_LINK_SCAN_INSTRUCTIONS to Simple(
            "Scan this with the Local Agent app on your phone " +
                "(Settings → Desktop Agent Connection → Scan desktop QR).",
        ),
        StringKeys.DESKTOP_LINK_PAIR_NOW to Simple("Pair Now"),
        StringKeys.DESKTOP_LINK_CODE_EXPIRES to Simple("Pairing code expires in %1\$ds."),
        StringKeys.DESKTOP_LINK_QR_WARNING to
            Simple("Only show this code to your phone — it lets a device pair for a short time."),
        StringKeys.DESKTOP_BACKUP_FILTER_DESC to Simple("JSON backup (*.json)"),
    )
}
