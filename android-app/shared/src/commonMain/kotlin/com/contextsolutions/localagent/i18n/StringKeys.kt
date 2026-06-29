package com.contextsolutions.localagent.i18n

/**
 * Stable IDs for every localizable string. Consumers reference
 * `StringKeys.X` — never a raw string literal — which gives find-usages,
 * rename safety, and lets the guardrail test assert the English floor
 * ([EnglishStrings]) defines exactly this set.
 *
 * Scope (PR #96): the `:shared` agent-layer surfaces — deterministic
 * formatters + `AgentLoop` user-facing replies. The ~525 Compose UI literals
 * and voice-command phrase lists are a documented follow-up and are NOT keyed
 * here yet.
 *
 * Naming: `area.thing[.variant]`. Placeholders use `%1$s` / `%2$s` (text) and
 * `%1$d` (count); `%%` is a literal percent.
 */
object StringKeys {

    // ── Shared across areas ──────────────────────────────────────────────
    const val COMMON_DONE = "common.done"
    const val COMMON_ERROR_GENERIC = "common.error_generic"
    const val COMMON_UNKNOWN_ERROR = "common.unknown_error"
    const val COMMON_SOURCE = "common.source"

    // ── Agent loop ───────────────────────────────────────────────────────
    const val AGENT_ENGINE_ERROR = "agent.engine_error"

    // ── Clock formatter + guidance ───────────────────────────────────────
    const val CLOCK_GUIDANCE = "clock.guidance"
    const val CLOCK_ALARMS_NONE = "clock.alarms.none"
    const val CLOCK_ALARMS_ONE = "clock.alarms.one"
    const val CLOCK_ALARMS_HEADER = "clock.alarms.header"
    const val CLOCK_TIMERS_NONE = "clock.timers.none"
    const val CLOCK_TIMERS_ONE = "clock.timers.one"
    const val CLOCK_TIMERS_HEADER = "clock.timers.header"
    const val CLOCK_ALARM_SET = "clock.alarm_set"
    const val CLOCK_TIMER_SET = "clock.timer_set"
    const val CLOCK_TIMER_REMAINING = "clock.timer_remaining"
    const val CLOCK_CANCELLED_ALARMS = "clock.cancelled_alarms"
    const val CLOCK_CANCELLED_TIMERS = "clock.cancelled_timers"
    const val CLOCK_DURATION_ZERO = "clock.duration.zero"
    const val CLOCK_DURATION_HOURS = "clock.duration.hours"
    const val CLOCK_DURATION_MINUTES = "clock.duration.minutes"
    const val CLOCK_DURATION_SECONDS = "clock.duration.seconds"

    // ── My List formatter + guidance ─────────────────────────────────────
    const val MYLIST_GUIDANCE = "mylist.guidance"
    const val MYLIST_ADDED = "mylist.added"
    const val MYLIST_DUE = "mylist.due"
    const val MYLIST_PRIORITY_HIGH = "mylist.priority.high"
    const val MYLIST_PRIORITY_MEDIUM = "mylist.priority.medium"
    const val MYLIST_PRIORITY_LOW = "mylist.priority.low"
    const val MYLIST_PRIORITY_TAG = "mylist.priority_tag"
    const val MYLIST_NONE_ALL = "mylist.none_all"
    const val MYLIST_NONE_OPEN = "mylist.none_open"
    const val MYLIST_ONE_HEADER = "mylist.one_header"
    const val MYLIST_HEADER = "mylist.header"
    const val MYLIST_DONE_MARKER = "mylist.done_marker"
    const val MYLIST_MARKED_DONE = "mylist.marked_done"
    const val MYLIST_REOPENED = "mylist.reopened"
    const val MYLIST_DELETED = "mylist.deleted"
    const val MYLIST_UPDATED = "mylist.updated"
    const val MYLIST_CLEAR_NONE = "mylist.clear_none"
    const val MYLIST_CLEARED = "mylist.cleared"
    const val MYLIST_DUE_TODAY = "mylist.due.today"
    const val MYLIST_DUE_TOMORROW = "mylist.due.tomorrow"
    const val MYLIST_DUE_YESTERDAY = "mylist.due.yesterday"

    // ── Weather formatter + prompts ──────────────────────────────────────
    const val WEATHER_LOCATION_PROMPT = "weather.location_prompt"
    const val WEATHER_HEADER = "weather.header"
    const val WEATHER_YOUR_AREA = "weather.your_area"
    const val WEATHER_ALERT_WARNING = "weather.alert.warning"
    const val WEATHER_ALERT_WATCH = "weather.alert.watch"
    const val WEATHER_ALERT_ADVISORY = "weather.alert.advisory"
    const val WEATHER_ALERT_STATEMENT = "weather.alert.statement"
    const val WEATHER_ALERT_GENERIC = "weather.alert.generic"
    const val WEATHER_NOW_PREFIX = "weather.now_prefix"
    const val WEATHER_HUMIDEX_LABEL = "weather.humidex_label"
    const val WEATHER_WIND = "weather.wind"
    const val WEATHER_HUMIDITY = "weather.humidity"
    const val WEATHER_UPDATED = "weather.updated"
    const val WEATHER_DISAMBIGUATION = "weather.disambiguation"

    // ── Stock (finance) formatter ────────────────────────────────────────
    const val STOCK_FALLBACK_NAME = "stock.fallback_name"
    const val STOCK_DAY_RANGE = "stock.day_range"
    const val STOCK_WEEK52_RANGE = "stock.week52_range"
    const val STOCK_MARKET_CAP = "stock.market_cap"
    const val STOCK_PE = "stock.pe"
    const val STOCK_VOLUME = "stock.volume"
    const val STOCK_AS_OF = "stock.as_of"

    // ── Inline "run job" command ─────────────────────────────────────────
    const val JOB_RUN_PROMPT = "job.run_prompt"
    const val JOB_NOT_FOUND = "job.not_found"
    const val JOB_NO_OUTPUT = "job.no_output"
    const val JOB_FAILED = "job.failed"

    // ── Memory remember/forget acks ──────────────────────────────────────
    const val MEMORY_ACK_REMEMBER = "memory.ack_remember"
    const val MEMORY_ACK_FORGET = "memory.ack_forget"

    // ── Common (UI) ──────────────────────────────────────────────────────
    const val COMMON_SAVE = "common.save"
    const val COMMON_CLEAR = "common.clear"
    const val COMMON_BACK = "common.back"
    const val COMMON_SHOW = "common.show"
    const val COMMON_HIDE = "common.hide"
    const val COMMON_MASK = "common.mask"
    const val COMMON_REVEAL = "common.reveal"
    const val COMMON_NONE = "common.none"

    // ── Settings (UI) ────────────────────────────────────────────────────
    const val SETTINGS_TITLE = "settings.title"
    const val SETTINGS_CONVERSATIONS_HEADER = "settings.conversations.header"
    const val SETTINGS_CONVERSATIONS_DESC = "settings.conversations.desc"
    const val SETTINGS_CONVERSATIONS_MANAGE = "settings.conversations.manage"
    const val SETTINGS_WEB_SEARCH_HEADER = "settings.web_search.header"
    const val SETTINGS_WEB_SEARCH_DESC = "settings.web_search.desc"
    const val SETTINGS_WEB_SEARCH_NO_KEY = "settings.web_search.no_key"
    const val SETTINGS_CACHE_HEADER = "settings.cache.header"
    const val SETTINGS_CACHE_LOADING = "settings.cache.loading"
    const val SETTINGS_CACHE_EMPTY = "settings.cache.empty"
    const val SETTINGS_CACHE_ENTRIES = "settings.cache.entries"
    const val SETTINGS_CACHE_DESC = "settings.cache.desc"
    const val SETTINGS_CACHE_CLEAR = "settings.cache.clear"
    const val SETTINGS_CACHE_CLEARED = "settings.cache.cleared"
    const val SETTINGS_SOURCES_HEADER = "settings.sources.header"
    const val SETTINGS_SOURCES_DESC = "settings.sources.desc"
    const val SETTINGS_SOURCES_MANAGE = "settings.sources.manage"
    const val SETTINGS_MEMORY_HEADER = "settings.memory.header"
    const val SETTINGS_MEMORY_LOADING = "settings.memory.loading"
    const val SETTINGS_MEMORY_NONE = "settings.memory.none"
    const val SETTINGS_MEMORY_ONE = "settings.memory.one"
    const val SETTINGS_MEMORY_MANY = "settings.memory.many"
    const val SETTINGS_MEMORY_CREATION_ON = "settings.memory.creation_on"
    const val SETTINGS_MEMORY_CREATION_OFF = "settings.memory.creation_off"
    const val SETTINGS_MEMORY_SUMMARY = "settings.memory.summary"
    const val SETTINGS_MEMORY_MANAGE = "settings.memory.manage"
    const val SETTINGS_APPEARANCE_HEADER = "settings.appearance.header"
    const val SETTINGS_APPEARANCE_DESC = "settings.appearance.desc"
    const val SETTINGS_APPEARANCE_LIGHT = "settings.appearance.light"
    const val SETTINGS_APPEARANCE_AUTO = "settings.appearance.auto"
    const val SETTINGS_APPEARANCE_DARK = "settings.appearance.dark"
    const val SETTINGS_LANGUAGE_LABEL = "settings.language.label"
    const val SETTINGS_FONT_LABEL = "settings.font.label"
    const val SETTINGS_FONT_SYSTEM = "settings.font.system"
    const val SETTINGS_FONT_SANS = "settings.font.sans"
    const val SETTINGS_FONT_SERIF = "settings.font.serif"
    const val SETTINGS_FONT_MONOSPACE = "settings.font.monospace"
    const val SETTINGS_FONT_SIZE = "settings.font.size"
    const val SETTINGS_FONT_PREVIEW = "settings.font.preview"
    const val SETTINGS_TELEMETRY_HEADER = "settings.telemetry.header"
    const val SETTINGS_TELEMETRY_ON = "settings.telemetry.on"
    const val SETTINGS_TELEMETRY_OFF = "settings.telemetry.off"
    const val SETTINGS_TELEMETRY_DETAIL = "settings.telemetry.detail"
    const val SETTINGS_TELEMETRY_DESC_NOTE = "settings.telemetry.desc_note"
    const val SETTINGS_BRAVE_HEADER = "settings.brave.header"
    const val SETTINGS_BRAVE_DESC_PRE = "settings.brave.desc_pre"
    const val SETTINGS_BRAVE_DESC_POST = "settings.brave.desc_post"
    const val SETTINGS_BRAVE_DESC_PRIVACY = "settings.brave.desc_privacy"
    const val SETTINGS_BRAVE_STATUS_USER = "settings.brave.status_user"
    const val SETTINGS_BRAVE_STATUS_DEV = "settings.brave.status_dev"
    const val SETTINGS_BRAVE_STATUS_NONE = "settings.brave.status_none"
    const val SETTINGS_BRAVE_FIELD_LABEL = "settings.brave.field_label"
    const val SETTINGS_BRAVE_PLACEHOLDER_REPLACE = "settings.brave.placeholder_replace"
    const val SETTINGS_BRAVE_PLACEHOLDER_PASTE = "settings.brave.placeholder_paste"
    const val SETTINGS_LINK_DESKTOP_HEADER = "settings.link.desktop_header"
    const val SETTINGS_LINK_DESKTOP_DESC_ANYWHERE = "settings.link.desktop_desc_anywhere"
    const val SETTINGS_LINK_DESKTOP_DESC_SUBSCRIBE = "settings.link.desktop_desc_subscribe"
    const val SETTINGS_LINK_SUBSCRIPTION_SETTINGS = "settings.link.subscription_settings"
    const val SETTINGS_LINK_UPGRADE = "settings.link.upgrade"
    const val SETTINGS_LINK_DESC_SYNC = "settings.link.desc_sync"
    const val SETTINGS_LINK_MOBILE_HEADER = "settings.link.mobile_header"
    const val SETTINGS_LINK_MOBILE_DESC = "settings.link.mobile_desc"
    const val SETTINGS_LINK_MOBILE_CONNECTED = "settings.link.mobile_connected"
    const val SETTINGS_LINK_MOBILE_OFFLINE = "settings.link.mobile_offline"
    const val SETTINGS_LINK_MOBILE_UNPAIRED = "settings.link.mobile_unpaired"
    const val SETTINGS_LINK_DISCONNECT = "settings.link.disconnect"
    const val SETTINGS_LINK_STATUS_OFF = "settings.link.status_off"
    const val SETTINGS_LINK_STATUS_NO_DESKTOP = "settings.link.status_no_desktop"
    const val SETTINGS_LINK_STATUS_CONNECTED = "settings.link.status_connected"
    const val SETTINGS_LINK_STATUS_UNREACHABLE = "settings.link.status_unreachable"
    const val SETTINGS_OLLAMA_HEADER = "settings.ollama.header"
    const val SETTINGS_OLLAMA_DISABLED_BY_LINK = "settings.ollama.disabled_by_link"
    const val SETTINGS_OLLAMA_DESC = "settings.ollama.desc"
    const val SETTINGS_OLLAMA_DESC_NOTE = "settings.ollama.desc_note"
    const val SETTINGS_OLLAMA_SSL_LOCKED = "settings.ollama.ssl_locked"
    const val SETTINGS_OLLAMA_SSL_LOCKED_BY_KEY = "settings.ollama.ssl_locked_by_key"
    const val SETTINGS_OLLAMA_SSL = "settings.ollama.ssl"
    const val SETTINGS_OLLAMA_HTTP_WARNING = "settings.ollama.http_warning"
    const val SETTINGS_OLLAMA_BASE_URL = "settings.ollama.base_url"
    const val SETTINGS_OLLAMA_HOST = "settings.ollama.host"
    const val SETTINGS_OLLAMA_PORT = "settings.ollama.port"
    const val SETTINGS_OLLAMA_OPENAI_HINT = "settings.ollama.openai_hint"
    const val SETTINGS_OLLAMA_TEST = "settings.ollama.test"
    const val SETTINGS_OLLAMA_CHAT_MODEL = "settings.ollama.chat_model"
    const val SETTINGS_OLLAMA_VISION_MODEL = "settings.ollama.vision_model"
    const val SETTINGS_OLLAMA_APIKEY_SET = "settings.ollama.apikey_set"
    const val SETTINGS_OLLAMA_APIKEY_REQUIRED = "settings.ollama.apikey_required"
    const val SETTINGS_OLLAMA_APIKEY_NONE = "settings.ollama.apikey_none"
    const val SETTINGS_OLLAMA_APIKEY_LABEL = "settings.ollama.apikey_label"
    const val SETTINGS_OLLAMA_APIKEY_LABEL_OPTIONAL = "settings.ollama.apikey_label_optional"
    const val SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_REPLACE = "settings.ollama.apikey_placeholder_replace"
    const val SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_PASTE = "settings.ollama.apikey_placeholder_paste"
    const val SETTINGS_OLLAMA_APIKEY_REQUIRES_SSL = "settings.ollama.apikey_requires_ssl"
    const val SETTINGS_OLLAMA_SAVE_KEY = "settings.ollama.save_key"
    const val SETTINGS_OLLAMA_CLEAR_KEY = "settings.ollama.clear_key"
    const val SETTINGS_OLLAMA_STATUS_CONNECTING = "settings.ollama.status_connecting"
    const val SETTINGS_OLLAMA_STATUS_CONNECTED = "settings.ollama.status_connected"
    const val SETTINGS_OLLAMA_STATUS_NO_MODELS_OPENAI = "settings.ollama.status_no_models_openai"
    const val SETTINGS_OLLAMA_STATUS_NO_MODELS = "settings.ollama.status_no_models"
    const val SETTINGS_OLLAMA_STATUS_FAILED = "settings.ollama.status_failed"
    const val SETTINGS_OLLAMA_STATUS_OFF = "settings.ollama.status_off"
    const val SETTINGS_OLLAMA_STATUS_ACTIVE = "settings.ollama.status_active"
    const val SETTINGS_OLLAMA_STATUS_NOT_CONFIGURED = "settings.ollama.status_not_configured"
    const val SETTINGS_OLLAMA_SERVER_TYPE_LABEL = "settings.ollama.server_type_label"
    const val SETTINGS_ABOUT_HEADER = "settings.about.header"
    const val SETTINGS_ABOUT_DESKTOP_AGENT = "settings.about.desktop_agent"
    const val SETTINGS_ABOUT_LOCAL_AGENT = "settings.about.local_agent"
    const val SETTINGS_ABOUT_BUILD_INFO = "settings.about.build_info"

    // ── Chat (UI) ────────────────────────────────────────────────────────
    const val CHAT_EMPTY_TITLE = "chat.empty_title"
    const val CHAT_EMPTY_BODY = "chat.empty_body"
    const val CHAT_INPUT_HINT = "chat.input_hint"
    const val CHAT_SEND = "chat.send"
    const val CHAT_CANCEL = "chat.cancel"
    const val CHAT_CANCELLING = "chat.cancelling"
    const val CHAT_DISMISS = "chat.dismiss"
    const val CHAT_OK = "chat.ok"
    const val CHAT_ERROR_PREFIX = "chat.error_prefix"
    const val CHAT_THINKING = "chat.thinking"
    const val CHAT_FROM_CACHE = "chat.from_cache"
    const val CHAT_SEARCHING = "chat.searching"
    const val CHAT_RUNNING_JOB = "chat.running_job"
    const val CHAT_SEARCH_FAILED = "chat.search_failed"
    const val CHAT_OVERFLOW_TITLE = "chat.overflow.title"
    const val CHAT_OVERFLOW_BODY = "chat.overflow.body"
    const val CHAT_OVERFLOW_CONTINUE = "chat.overflow.continue"
    const val CHAT_OVERFLOW_START_NEW = "chat.overflow.start_new"
    const val CHAT_MEMORY_LIMIT_TITLE = "chat.memory_limit.title"
    const val CHAT_MEMORY_LIMIT_BODY = "chat.memory_limit.body"
    const val CHAT_MEMORY_PROMPT_SAVE_AS = "chat.memory_prompt.save_as"
    const val CHAT_CATEGORY_PERSONAL_IDENTITY = "chat.category.personal_identity"
    const val CHAT_CATEGORY_PREFERENCE = "chat.category.preference"
    const val CHAT_CATEGORY_PROFESSIONAL = "chat.category.professional"
    const val CHAT_CATEGORY_INTEREST = "chat.category.interest"
    const val CHAT_CATEGORY_RELATIONSHIP = "chat.category.relationship"
    const val CHAT_CATEGORY_TEMPORARY_CONTEXT = "chat.category.temporary_context"
    const val CHAT_SESSION_UNLOADED = "chat.session.unloaded"
    const val CHAT_SESSION_DOWNLOADING = "chat.session.downloading"
    const val CHAT_SESSION_LOADING = "chat.session.loading"
    const val CHAT_SESSION_LOADED_CPU = "chat.session.loaded_cpu"
    const val CHAT_SESSION_LOADED = "chat.session.loaded"
    const val CHAT_SESSION_FAILED = "chat.session.failed"
    // Mobile-only short forms (PR #22) — a small fixed set shown on Android's chat
    // banner. Desktop keeps the accurate per-accelerator strings above.
    const val CHAT_SESSION_MOBILE_UNLOADED = "chat.session.mobile.unloaded"
    const val CHAT_SESSION_MOBILE_DOWNLOADING = "chat.session.mobile.downloading"
    const val CHAT_SESSION_MOBILE_LOADING = "chat.session.mobile.loading"
    const val CHAT_SESSION_MOBILE_LOADED_GPU = "chat.session.mobile.loaded_gpu"
    const val CHAT_SESSION_MOBILE_LOADED_CPU = "chat.session.mobile.loaded_cpu"
    const val CHAT_THERMAL_WARM = "chat.thermal.warm"
    const val CHAT_THERMAL_BLOCK_TITLE = "chat.thermal.block_title"
    const val CHAT_THERMAL_BLOCK_BODY = "chat.thermal.block_body"
    const val CHAT_CD_NEW_CHAT = "chat.cd.new_chat"
    const val CHAT_CD_MYLIST = "chat.cd.mylist"
    const val CHAT_CD_TIMERS = "chat.cd.timers"
    const val CHAT_CD_ALARMS = "chat.cd.alarms"
    const val CHAT_CD_JOBS = "chat.cd.jobs"
    const val CHAT_CD_SETTINGS = "chat.cd.settings"
    const val CHAT_CD_JUMP_TO_LATEST = "chat.cd.jump_to_latest"
    const val CHAT_CD_ATTACHED_IMAGE = "chat.cd.attached_image"
    const val CHAT_CD_ATTACH_IMAGE = "chat.cd.attach_image"
    const val CHAT_CD_REMOVE_IMAGE = "chat.cd.remove_image"
    const val CHAT_CD_CLEAR_INPUT = "chat.cd.clear_input"
    const val CHAT_CD_DELETE_TURN = "chat.cd.delete_turn"
    const val CHAT_DELETE_TURN_TITLE = "chat.delete_turn.title"
    const val CHAT_DELETE_TURN_BODY = "chat.delete_turn.body"
    const val CHAT_DELETE_TURN_CONFIRM = "chat.delete_turn.confirm"
    const val CHAT_DELETE_TURN_CANCEL = "chat.delete_turn.cancel"
    const val CHAT_CD_MIC_START = "chat.cd.mic_start"
    const val CHAT_CD_MIC_STOP = "chat.cd.mic_stop"
    const val CHAT_CD_TTS_ENABLE = "chat.cd.tts_enable"
    const val CHAT_CD_TTS_DISABLE = "chat.cd.tts_disable"
    const val CHAT_CD_DISMISS_THERMAL = "chat.cd.dismiss_thermal"
    const val CHAT_CD_MEM_HEALTHY = "chat.cd.mem_healthy"
    const val CHAT_CD_MEM_CAUTION = "chat.cd.mem_caution"
    const val CHAT_CD_MEM_LOW = "chat.cd.mem_low"
    const val CHAT_CD_LINK_CONNECTED = "chat.cd.link_connected"
    const val CHAT_CD_LINK_UNREACHABLE = "chat.cd.link_unreachable"

    // ── Onboarding (UI) ──────────────────────────────────────────────────
    const val ONBOARDING_NAV_CONTINUE = "onboarding.nav.continue"
    const val ONBOARDING_NAV_SKIP_SETTINGS = "onboarding.nav.skip_settings"
    const val ONBOARDING_LANGUAGE_TITLE = "onboarding.language.title"
    const val ONBOARDING_LANGUAGE_BODY = "onboarding.language.body"

    // ── Onboarding — model download (first-run "Set up the on-device models") ──
    const val DOWNLOAD_TITLE = "download.title"
    const val DOWNLOAD_INTRO = "download.intro"
    const val DOWNLOAD_MODELS_HEADER = "download.models_header"
    const val DOWNLOAD_TOTAL = "download.total"
    const val DOWNLOAD_SPEC_INCOMPLETE = "download.spec_incomplete"
    const val DOWNLOAD_STATE_IDLE = "download.state_idle"
    const val DOWNLOAD_STATE_QUEUED = "download.state_queued"
    const val DOWNLOAD_STATE_STARTING = "download.state_starting"
    const val DOWNLOAD_STATE_COMPLETED = "download.state_completed"
    const val DOWNLOAD_PROGRESS = "download.progress"
    const val DOWNLOAD_FAILED = "download.failed"
    const val DOWNLOAD_ACTION_WIFI = "download.action_wifi"
    const val DOWNLOAD_ACTION_CELLULAR = "download.action_cellular"
    const val DOWNLOAD_ACTION_PAUSE = "download.action_pause"
    const val DOWNLOAD_ACTION_RETRY_WIFI = "download.action_retry_wifi"
    const val DOWNLOAD_ACTION_RETRY_CELLULAR = "download.action_retry_cellular"
    const val DOWNLOAD_ERROR_NETWORK = "download.error_network"
    const val DOWNLOAD_ERROR_HTTP_CLIENT = "download.error_http_client"
    const val DOWNLOAD_ERROR_HTTP_SERVER = "download.error_http_server"
    const val DOWNLOAD_ERROR_STORAGE = "download.error_storage"
    const val DOWNLOAD_ERROR_CHECKSUM = "download.error_checksum"
    const val DOWNLOAD_ERROR_MISCONFIGURED = "download.error_misconfigured"

    // ── Memory (UI) ──────────────────────────────────────────────────────
    const val MEMORY_TITLE = "memory.title"
    const val MEMORY_CONVERSATION_TITLE = "memory.conversation_title"
    const val MEMORY_EMPTY = "memory.empty"
    const val MEMORY_CONVERSATION_EMPTY = "memory.conversation_empty"
    const val MEMORY_LOADING = "memory.loading"
    const val MEMORY_CREATION_TOGGLE = "memory.creation_toggle"
    const val MEMORY_CLEAR_ALL = "memory.clear_all"
    const val MEMORY_CLEAR_ALL_TITLE = "memory.clear_all_title"
    const val MEMORY_CLEAR_ALL_BODY = "memory.clear_all_body"
    const val MEMORY_EXPORT = "memory.export"
    const val MEMORY_IMPORT = "memory.import"
    const val MEMORY_TOAST_NOTHING_EXPORT = "memory.toast.nothing_export"
    const val MEMORY_TOAST_EXPORTED = "memory.toast.exported"
    const val MEMORY_TOAST_IMPORTED = "memory.toast.imported"
    const val MEMORY_TOAST_IMPORTED_SKIPPED = "memory.toast.imported_skipped"
    const val MEMORY_DELETE_TITLE = "memory.delete_title"
    const val MEMORY_DELETE_BODY = "memory.delete_body"
    const val MEMORY_DELETE = "memory.delete"
    const val MEMORY_CANCEL = "memory.cancel"
    const val MEMORY_OK = "memory.ok"
    const val MEMORY_IMPORT_TITLE = "memory.import_title"
    const val MEMORY_IMPORT_BODY = "memory.import_body"
    const val MEMORY_CHOOSE_FILE = "memory.choose_file"
    const val MEMORY_IMPORT_CAP_TITLE = "memory.import_cap_title"
    const val MEMORY_IMPORT_CAP_BODY = "memory.import_cap_body"
    const val MEMORY_CREATED = "memory.created"
    const val MEMORY_CREATED_EXPIRES = "memory.created_expires"
    const val MEMORY_CD_MORE = "memory.cd.more"
    const val MEMORY_CD_DELETE = "memory.cd.delete"
    const val MEMORY_CATEGORY_PERSONAL_IDENTITY = "memory.category.personal_identity"
    const val MEMORY_CATEGORY_PREFERENCE = "memory.category.preference"
    const val MEMORY_CATEGORY_PROFESSIONAL = "memory.category.professional"
    const val MEMORY_CATEGORY_INTEREST = "memory.category.interest"
    const val MEMORY_CATEGORY_RELATIONSHIP = "memory.category.relationship"
    const val MEMORY_CATEGORY_TEMPORARY_CONTEXT = "memory.category.temporary_context"
    const val MEMORY_CATEGORY_SHORT_PERSONAL_IDENTITY = "memory.category_short.personal_identity"
    const val MEMORY_CATEGORY_SHORT_PREFERENCE = "memory.category_short.preference"
    const val MEMORY_CATEGORY_SHORT_PROFESSIONAL = "memory.category_short.professional"
    const val MEMORY_CATEGORY_SHORT_INTEREST = "memory.category_short.interest"
    const val MEMORY_CATEGORY_SHORT_RELATIONSHIP = "memory.category_short.relationship"
    const val MEMORY_CATEGORY_SHORT_TEMPORARY_CONTEXT = "memory.category_short.temporary_context"

    // ── Clock (UI) ───────────────────────────────────────────────────────
    const val CLOCK_UI_ALARMS_TITLE = "clock.ui.alarms_title"
    const val CLOCK_UI_ALARMS_EMPTY = "clock.ui.alarms_empty"
    const val CLOCK_UI_ALARMS_EMPTY_HINT = "clock.ui.alarms_empty_hint"
    const val CLOCK_UI_TIMERS_TITLE = "clock.ui.timers_title"
    const val CLOCK_UI_TIMERS_EMPTY = "clock.ui.timers_empty"
    const val CLOCK_UI_TIMERS_EMPTY_HINT = "clock.ui.timers_empty_hint"
    const val CLOCK_UI_ONCE = "clock.ui.once"
    const val CLOCK_UI_WEEKDAYS = "clock.ui.weekdays"
    const val CLOCK_UI_WEEKENDS = "clock.ui.weekends"
    const val CLOCK_UI_EVERY_DAY = "clock.ui.every_day"
    const val CLOCK_UI_OFF_SUFFIX = "clock.ui.off_suffix"
    const val CLOCK_UI_EDIT = "clock.ui.edit"
    const val CLOCK_UI_CANCEL = "clock.ui.cancel"
    const val CLOCK_UI_ADD = "clock.ui.add"
    const val CLOCK_UI_NEW_ALARM = "clock.ui.new_alarm"
    const val CLOCK_UI_EDIT_ALARM = "clock.ui.edit_alarm"
    const val CLOCK_UI_LABEL_OPTIONAL = "clock.ui.label_optional"
    const val CLOCK_UI_NEW_TIMER = "clock.ui.new_timer"
    const val CLOCK_UI_TIMER_DEFAULT_NAME = "clock.ui.timer_default_name"
    const val CLOCK_UI_EXTEND_1MIN = "clock.ui.extend_1min"
    const val CLOCK_UI_EXTEND_5MIN = "clock.ui.extend_5min"
    const val CLOCK_UI_DURATION_H = "clock.ui.duration_h"
    const val CLOCK_UI_DURATION_M = "clock.ui.duration_m"
    const val CLOCK_UI_DURATION_S = "clock.ui.duration_s"
    const val CLOCK_UI_START = "clock.ui.start"
    const val CLOCK_UI_CD_NEW_ALARM = "clock.ui.cd.new_alarm"
    const val CLOCK_UI_CD_NEW_TIMER = "clock.ui.cd.new_timer"
    const val CLOCK_UI_DAY_LETTER_SUN = "clock.ui.day_letter.sun"
    const val CLOCK_UI_DAY_LETTER_MON = "clock.ui.day_letter.mon"
    const val CLOCK_UI_DAY_LETTER_TUE = "clock.ui.day_letter.tue"
    const val CLOCK_UI_DAY_LETTER_WED = "clock.ui.day_letter.wed"
    const val CLOCK_UI_DAY_LETTER_THU = "clock.ui.day_letter.thu"
    const val CLOCK_UI_DAY_LETTER_FRI = "clock.ui.day_letter.fri"
    const val CLOCK_UI_DAY_LETTER_SAT = "clock.ui.day_letter.sat"
    const val CLOCK_UI_DAY_SHORT_MON = "clock.ui.day_short.mon"
    const val CLOCK_UI_DAY_SHORT_TUE = "clock.ui.day_short.tue"
    const val CLOCK_UI_DAY_SHORT_WED = "clock.ui.day_short.wed"
    const val CLOCK_UI_DAY_SHORT_THU = "clock.ui.day_short.thu"
    const val CLOCK_UI_DAY_SHORT_FRI = "clock.ui.day_short.fri"
    const val CLOCK_UI_DAY_SHORT_SAT = "clock.ui.day_short.sat"
    const val CLOCK_UI_DAY_SHORT_SUN = "clock.ui.day_short.sun"

    // ── My List (UI) ─────────────────────────────────────────────────────
    const val MYLIST_UI_TITLE = "mylist.ui.title"
    const val MYLIST_UI_EMPTY = "mylist.ui.empty"
    const val MYLIST_UI_EMPTY_HINT = "mylist.ui.empty_hint"
    const val MYLIST_UI_CLEAR_DONE = "mylist.ui.clear_done"
    const val MYLIST_UI_DELETE_TITLE = "mylist.ui.delete_title"
    const val MYLIST_UI_DELETE_BODY = "mylist.ui.delete_body"
    const val MYLIST_UI_DELETE = "mylist.ui.delete"
    const val MYLIST_UI_CANCEL = "mylist.ui.cancel"
    const val MYLIST_UI_NEW_ITEM = "mylist.ui.new_item"
    const val MYLIST_UI_EDIT_ITEM = "mylist.ui.edit_item"
    const val MYLIST_UI_TITLE_LABEL = "mylist.ui.title_label"
    const val MYLIST_UI_PRIORITY = "mylist.ui.priority"
    const val MYLIST_UI_DUE_PREFIX = "mylist.ui.due_prefix"
    const val MYLIST_UI_SET = "mylist.ui.set"
    const val MYLIST_UI_CHANGE = "mylist.ui.change"
    const val MYLIST_UI_NOTES_OPTIONAL = "mylist.ui.notes_optional"
    const val MYLIST_UI_ADD = "mylist.ui.add"
    const val MYLIST_UI_OK = "mylist.ui.ok"
    const val MYLIST_UI_CD_ADD = "mylist.ui.cd.add"
    const val MYLIST_UI_CD_EDIT = "mylist.ui.cd.edit"
    const val MYLIST_UI_CD_DELETE = "mylist.ui.cd.delete"

    // ── History (UI) ─────────────────────────────────────────────────────
    const val HISTORY_TITLE = "history.title"
    const val HISTORY_EMPTY = "history.empty"
    const val HISTORY_CAPACITY_FOOTNOTE = "history.capacity_footnote"
    const val HISTORY_DELETE_TITLE = "history.delete_title"
    const val HISTORY_DELETE_BODY = "history.delete_body"
    const val HISTORY_DELETE = "history.delete"
    const val HISTORY_CANCEL = "history.cancel"
    const val HISTORY_CD_DELETE = "history.cd.delete"
    // PR #8 — conversation search affordance.
    const val HISTORY_SEARCH_HINT = "history.search_hint"
    const val HISTORY_CD_SEARCH = "history.cd.search"
    const val HISTORY_CD_CLOSE_SEARCH = "history.cd.close_search"
    const val HISTORY_CD_CLEAR_SEARCH = "history.cd.clear_search"
    const val HISTORY_SEARCH_EMPTY = "history.search_empty"

    // ── Jobs (UI) ────────────────────────────────────────────────────────
    const val JOBS_TITLE = "jobs.title"
    const val JOBS_CD_ADD = "jobs.cd.add"
    const val JOBS_DELETE_TITLE = "jobs.delete_title"
    const val JOBS_DELETE_BODY = "jobs.delete_body"
    const val JOBS_DELETE = "jobs.delete"
    const val JOBS_CANCEL = "jobs.cancel"
    const val JOBS_SYNCED = "jobs.synced"
    const val JOBS_NEVER_SYNCED = "jobs.never_synced"
    const val JOBS_STATUS_ONLINE = "jobs.status.online"
    const val JOBS_STATUS_OFFLINE = "jobs.status.offline"
    const val JOBS_STATUS_NOT_LINKED = "jobs.status.not_linked"
    const val JOBS_EMPTY = "jobs.empty"
    const val JOBS_EMPTY_HINT_ADMIN = "jobs.empty_hint_admin"
    const val JOBS_EMPTY_HINT_REMOTE = "jobs.empty_hint_remote"
    const val JOBS_RUNNING = "jobs.running"
    const val JOBS_STATUS_RUNNING = "jobs.status.running"
    const val JOBS_STATUS_SUCCEEDED = "jobs.status.succeeded"
    const val JOBS_STATUS_FAILED = "jobs.status.failed"
    const val JOBS_STATUS_CANCELLED = "jobs.status.cancelled"
    const val JOBS_VIEW_CONVERSATION = "jobs.view_conversation"
    const val JOBS_CD_RUN_NOW = "jobs.cd.run_now"
    const val JOBS_CD_CANCEL_RUN = "jobs.cd.cancel_run"
    const val JOBS_CD_EDIT = "jobs.cd.edit"
    const val JOBS_CD_DELETE = "jobs.cd.delete"
    const val JOBS_SCHED_DAILY_AT = "jobs.sched.daily_at"
    const val JOBS_SCHED_AT = "jobs.sched.at"
    const val JOBS_SCHED_CRON_RAW = "jobs.sched.cron_raw"
    const val JOBS_REPEAT = "jobs.repeat"
    const val JOBS_SCHED_ONCE_ON = "jobs.sched.once_on"
    const val JOBS_ONCE = "jobs.once"
    const val JOBS_DAY_SHORT_SUN = "jobs.day_short.sun"
    const val JOBS_DAY_SHORT_MON = "jobs.day_short.mon"
    const val JOBS_DAY_SHORT_TUE = "jobs.day_short.tue"
    const val JOBS_DAY_SHORT_WED = "jobs.day_short.wed"
    const val JOBS_DAY_SHORT_THU = "jobs.day_short.thu"
    const val JOBS_DAY_SHORT_FRI = "jobs.day_short.fri"
    const val JOBS_DAY_SHORT_SAT = "jobs.day_short.sat"
    const val JOBS_DAY_LETTER_SUN = "jobs.day_letter.sun"
    const val JOBS_DAY_LETTER_MON = "jobs.day_letter.mon"
    const val JOBS_DAY_LETTER_TUE = "jobs.day_letter.tue"
    const val JOBS_DAY_LETTER_WED = "jobs.day_letter.wed"
    const val JOBS_DAY_LETTER_THU = "jobs.day_letter.thu"
    const val JOBS_DAY_LETTER_FRI = "jobs.day_letter.fri"
    const val JOBS_DAY_LETTER_SAT = "jobs.day_letter.sat"
    const val JOBS_FORM_NEW = "jobs.form.new"
    const val JOBS_FORM_EDIT = "jobs.form.edit"
    const val JOBS_FORM_CREATE = "jobs.form.create"
    const val JOBS_FORM_NAME = "jobs.form.name"
    const val JOBS_FORM_LOCATION = "jobs.form.location"
    const val JOBS_FORM_CHOOSE_TOOLTIP = "jobs.form.choose_tooltip"
    const val JOBS_FORM_CHOOSE_JOB = "jobs.form.choose_job"
    const val JOBS_FORM_KEYWORDS = "jobs.form.keywords"
    const val JOBS_FORM_SCHEDULE = "jobs.form.schedule"
    const val JOBS_FORM_AM = "jobs.form.am"
    const val JOBS_FORM_PM = "jobs.form.pm"
    const val JOBS_FORM_HOUR = "jobs.form.hour"
    const val JOBS_FORM_MIN = "jobs.form.min"
    const val JOBS_FORM_RUNS_DAILY_AT = "jobs.form.runs_daily_at"
    const val JOBS_FORM_RUNS_DAYS_AT = "jobs.form.runs_days_at"
    const val JOBS_FORM_RUN_ONCE_AFTER = "jobs.form.run_once_after"
    const val JOBS_FORM_DUR_H = "jobs.form.dur_h"
    const val JOBS_FORM_DUR_M = "jobs.form.dur_m"
    const val JOBS_FORM_DUR_S = "jobs.form.dur_s"
    // Choose Job catalog + pre-save init (PR #100, desktop-only).
    const val JOBS_CHOOSE_TITLE = "jobs.choose.title"
    const val JOBS_CHOOSE_LOADING = "jobs.choose.loading"
    const val JOBS_CHOOSE_EMPTY = "jobs.choose.empty"
    const val JOBS_CHOOSE_UNAVAILABLE_OS = "jobs.choose.unavailable_os"
    const val JOBS_INIT_SETTING_UP = "jobs.init.setting_up"
    const val JOBS_INIT_AWAIT_USER = "jobs.init.await_user"
    const val JOBS_INIT_FAILED_TITLE = "jobs.init.failed_title"
    const val JOBS_INIT_INTRO = "jobs.init.intro"
    const val JOBS_INIT_NONE = "jobs.init.none"
    const val JOBS_INIT_APPROVE = "jobs.init.approve"

    // ── Search sources (UI) ──────────────────────────────────────────────
    const val SEARCH_SOURCES_TITLE = "search_sources.title"
    const val SEARCH_SOURCES_COUNTRY_LABEL = "search_sources.country_label"
    const val SEARCH_SOURCES_GENERAL = "search_sources.general"
    const val SEARCH_SOURCES_NEWS = "search_sources.news"
    const val SEARCH_SOURCES_WEATHER = "search_sources.weather"
    const val SEARCH_SOURCES_SPORTS = "search_sources.sports"
    const val SEARCH_SOURCES_FINANCE = "search_sources.finance"
    const val SEARCH_SOURCES_NONE_CONFIGURED = "search_sources.none_configured"
    const val SEARCH_SOURCES_ADD = "search_sources.add"
    const val SEARCH_SOURCES_CD_EDIT = "search_sources.cd.edit"
    const val SEARCH_SOURCES_CD_REMOVE = "search_sources.cd.remove"
    const val SEARCH_SOURCES_DIALOG_TITLE_ADD = "search_sources.dialog.title_add"
    const val SEARCH_SOURCES_DIALOG_TITLE_EDIT = "search_sources.dialog.title_edit"
    const val SEARCH_SOURCES_DIALOG_ADD = "search_sources.dialog.add"
    const val SEARCH_SOURCES_DIALOG_CANCEL = "search_sources.dialog.cancel"
    const val SEARCH_SOURCES_DIALOG_DOMAIN = "search_sources.dialog.domain"
    const val SEARCH_SOURCES_DIALOG_DISPLAY_NAME = "search_sources.dialog.display_name"
    const val SEARCH_SOURCES_DIALOG_KIND = "search_sources.dialog.kind"
    const val SEARCH_SOURCES_DIALOG_ENDPOINT = "search_sources.dialog.endpoint"
    const val SEARCH_SOURCES_DIALOG_TEMPLATE_HINT = "search_sources.dialog.template_hint"

    // ── Relative-time / date formatting (UI) ─────────────────────────────
    const val FMT_NOW = "fmt.now"
    const val FMT_AGO = "fmt.ago"
    const val FMT_IN = "fmt.in"
    const val FMT_MONTHS = "fmt.months"
    const val FMT_DATE_SHORT = "fmt.date_short"
    const val FMT_DATE_YEAR = "fmt.date_year"

    // ── Notifications (titles; bodies stay as data) ──────────────────────
    const val NOTIF_JOB_FINISHED = "notif.job.finished"
    const val NOTIF_JOB_FAILED = "notif.job.failed"
    const val NOTIF_TASK_COMPLETE = "notif.task.complete"
    const val NOTIF_TASK_CANCELLED = "notif.task.cancelled"
    const val NOTIF_TASK_FAILED = "notif.task.failed"
    const val NOTIF_MODEL_DOWNLOADING = "notif.model.downloading"
    const val NOTIF_MODEL_STARTING = "notif.model.starting"
    const val NOTIF_MODEL_READY = "notif.model.ready"
    const val NOTIF_MODEL_COMPLETE = "notif.model.complete"
    const val NOTIF_MODEL_FAILED = "notif.model.failed"
    const val NOTIF_MODELS_READY = "notif.models.ready"
    const val NOTIF_MODELS_ALL_DONE = "notif.models.all_done"
    const val NOTIF_MODELS_INCOMPLETE = "notif.models.incomplete"
    const val NOTIF_MODELS_FAILED_COUNT = "notif.models.failed_count"

    // ── Data-display names (locations.json country names) ────────────────
    const val DATA_COUNTRY_CA = "data.country.ca"
    const val DATA_COUNTRY_US = "data.country.us"
    const val DATA_COUNTRY_GB = "data.country.gb"
    const val DATA_COUNTRY_AU = "data.country.au"

    // ── Desktop sections (UI) ────────────────────────────────────────────
    const val DESKTOP_VOICE_SECTION_HEADER = "desktop.voice.section_header"
    const val DESKTOP_VOICE_DESCRIPTION = "desktop.voice.description"
    const val DESKTOP_VOICE_ENGINE_PIPER = "desktop.voice.engine_piper"
    const val DESKTOP_VOICE_SYSTEM_DEFAULT = "desktop.voice.system_default"
    const val DESKTOP_VOICE_ENGINE_LABEL = "desktop.voice.engine_label"
    const val DESKTOP_VOICE_VOICE_LABEL = "desktop.voice.voice_label"
    const val DESKTOP_VOICE_SPEECH_RATE = "desktop.voice.speech_rate"
    const val DESKTOP_VOICE_RATE_NORMAL = "desktop.voice.rate_normal"
    const val DESKTOP_VOICE_RATE_SLOWER = "desktop.voice.rate_slower"
    const val DESKTOP_VOICE_RATE_FASTER = "desktop.voice.rate_faster"
    const val DESKTOP_VOICE_TEST_VOICE = "desktop.voice.test_voice"
    const val DESKTOP_VOICE_TEST_UTTERANCE = "desktop.voice.test_utterance"
    const val DESKTOP_VOICE_DOWNLOADING = "desktop.voice.downloading"
    const val DESKTOP_VOICE_READY = "desktop.voice.ready"
    const val DESKTOP_VOICE_FAILED = "desktop.voice.failed"
    const val DESKTOP_VOICE_UNAVAILABLE = "desktop.voice.unavailable"
    const val DESKTOP_VOICE_IDLE = "desktop.voice.idle"
    const val DESKTOP_VOICE_FILTER = "desktop.voice.filter"
    const val DESKTOP_VOICE_SHOWING = "desktop.voice.showing"
    const val DESKTOP_GPU_TITLE = "desktop.gpu.title"
    const val DESKTOP_GPU_DESCRIPTION = "desktop.gpu.description"
    const val DESKTOP_GPU_DETECT_DEVICES = "desktop.gpu.detect_devices"
    const val DESKTOP_GPU_AUTO_ALL = "desktop.gpu.auto_all"
    const val DESKTOP_GPU_DEVICE_PREFIX = "desktop.gpu.device_prefix"
    const val DESKTOP_GPU_PINNED = "desktop.gpu.pinned"
    const val DESKTOP_GPU_DETECTION_FAILED = "desktop.gpu.detection_failed"
    const val DESKTOP_GPU_NO_DEVICES = "desktop.gpu.no_devices"
    const val DESKTOP_LINK_SUBSCRIBE_PROMPT = "desktop.link.subscribe_prompt"
    const val DESKTOP_LINK_QR_CD = "desktop.link.qr_cd"
    const val DESKTOP_LINK_SCAN_INSTRUCTIONS = "desktop.link.scan_instructions"
    const val DESKTOP_LINK_PAIR_NOW = "desktop.link.pair_now"
    const val DESKTOP_LINK_CODE_EXPIRES = "desktop.link.code_expires"
    const val DESKTOP_LINK_QR_WARNING = "desktop.link.qr_warning"
    const val DESKTOP_BACKUP_FILTER_DESC = "desktop.backup.filter_desc"

    /** Every key above — the guardrail test asserts the English floor covers exactly this set. */
    val ALL: List<String> = listOf(
        COMMON_DONE, COMMON_ERROR_GENERIC, COMMON_UNKNOWN_ERROR, COMMON_SOURCE,
        AGENT_ENGINE_ERROR,
        CLOCK_GUIDANCE, CLOCK_ALARMS_NONE, CLOCK_ALARMS_ONE, CLOCK_ALARMS_HEADER,
        CLOCK_TIMERS_NONE, CLOCK_TIMERS_ONE, CLOCK_TIMERS_HEADER,
        CLOCK_ALARM_SET, CLOCK_TIMER_SET, CLOCK_TIMER_REMAINING,
        CLOCK_CANCELLED_ALARMS, CLOCK_CANCELLED_TIMERS,
        CLOCK_DURATION_ZERO, CLOCK_DURATION_HOURS, CLOCK_DURATION_MINUTES, CLOCK_DURATION_SECONDS,
        MYLIST_GUIDANCE, MYLIST_ADDED, MYLIST_DUE,
        MYLIST_PRIORITY_HIGH, MYLIST_PRIORITY_MEDIUM, MYLIST_PRIORITY_LOW, MYLIST_PRIORITY_TAG,
        MYLIST_NONE_ALL, MYLIST_NONE_OPEN, MYLIST_ONE_HEADER, MYLIST_HEADER, MYLIST_DONE_MARKER,
        MYLIST_MARKED_DONE, MYLIST_REOPENED, MYLIST_DELETED, MYLIST_UPDATED,
        MYLIST_CLEAR_NONE, MYLIST_CLEARED,
        MYLIST_DUE_TODAY, MYLIST_DUE_TOMORROW, MYLIST_DUE_YESTERDAY,
        WEATHER_LOCATION_PROMPT, WEATHER_HEADER, WEATHER_YOUR_AREA,
        WEATHER_ALERT_WARNING, WEATHER_ALERT_WATCH, WEATHER_ALERT_ADVISORY,
        WEATHER_ALERT_STATEMENT, WEATHER_ALERT_GENERIC,
        WEATHER_NOW_PREFIX, WEATHER_HUMIDEX_LABEL, WEATHER_WIND, WEATHER_HUMIDITY,
        WEATHER_UPDATED, WEATHER_DISAMBIGUATION,
        STOCK_FALLBACK_NAME, STOCK_DAY_RANGE, STOCK_WEEK52_RANGE,
        STOCK_MARKET_CAP, STOCK_PE, STOCK_VOLUME, STOCK_AS_OF,
        JOB_RUN_PROMPT, JOB_NOT_FOUND, JOB_NO_OUTPUT, JOB_FAILED,
        MEMORY_ACK_REMEMBER, MEMORY_ACK_FORGET,
        // ── Common (UI) ──
        COMMON_SAVE, COMMON_CLEAR, COMMON_BACK, COMMON_SHOW, COMMON_HIDE,
        COMMON_MASK, COMMON_REVEAL, COMMON_NONE,
        // ── Settings (UI) ──
        SETTINGS_TITLE,
        SETTINGS_CONVERSATIONS_HEADER, SETTINGS_CONVERSATIONS_DESC, SETTINGS_CONVERSATIONS_MANAGE,
        SETTINGS_WEB_SEARCH_HEADER, SETTINGS_WEB_SEARCH_DESC, SETTINGS_WEB_SEARCH_NO_KEY,
        SETTINGS_CACHE_HEADER, SETTINGS_CACHE_LOADING, SETTINGS_CACHE_EMPTY, SETTINGS_CACHE_ENTRIES,
        SETTINGS_CACHE_DESC, SETTINGS_CACHE_CLEAR, SETTINGS_CACHE_CLEARED,
        SETTINGS_SOURCES_HEADER, SETTINGS_SOURCES_DESC, SETTINGS_SOURCES_MANAGE,
        SETTINGS_MEMORY_HEADER, SETTINGS_MEMORY_LOADING, SETTINGS_MEMORY_NONE, SETTINGS_MEMORY_ONE,
        SETTINGS_MEMORY_MANY, SETTINGS_MEMORY_CREATION_ON, SETTINGS_MEMORY_CREATION_OFF,
        SETTINGS_MEMORY_SUMMARY, SETTINGS_MEMORY_MANAGE,
        SETTINGS_APPEARANCE_HEADER, SETTINGS_APPEARANCE_DESC,
        SETTINGS_APPEARANCE_LIGHT, SETTINGS_APPEARANCE_AUTO, SETTINGS_APPEARANCE_DARK,
        SETTINGS_LANGUAGE_LABEL,
        SETTINGS_FONT_LABEL, SETTINGS_FONT_SYSTEM, SETTINGS_FONT_SANS, SETTINGS_FONT_SERIF,
        SETTINGS_FONT_MONOSPACE, SETTINGS_FONT_SIZE, SETTINGS_FONT_PREVIEW,
        SETTINGS_TELEMETRY_HEADER, SETTINGS_TELEMETRY_ON, SETTINGS_TELEMETRY_OFF, SETTINGS_TELEMETRY_DETAIL,
        SETTINGS_TELEMETRY_DESC_NOTE,
        SETTINGS_BRAVE_HEADER, SETTINGS_BRAVE_DESC_PRE, SETTINGS_BRAVE_DESC_POST, SETTINGS_BRAVE_DESC_PRIVACY,
        SETTINGS_BRAVE_STATUS_USER, SETTINGS_BRAVE_STATUS_DEV, SETTINGS_BRAVE_STATUS_NONE,
        SETTINGS_BRAVE_FIELD_LABEL, SETTINGS_BRAVE_PLACEHOLDER_REPLACE, SETTINGS_BRAVE_PLACEHOLDER_PASTE,
        SETTINGS_LINK_DESKTOP_HEADER, SETTINGS_LINK_DESKTOP_DESC_ANYWHERE, SETTINGS_LINK_DESKTOP_DESC_SUBSCRIBE,
        SETTINGS_LINK_SUBSCRIPTION_SETTINGS, SETTINGS_LINK_UPGRADE,
        SETTINGS_LINK_DESC_SYNC,
        SETTINGS_LINK_MOBILE_HEADER, SETTINGS_LINK_MOBILE_DESC,
        SETTINGS_LINK_MOBILE_CONNECTED, SETTINGS_LINK_MOBILE_OFFLINE, SETTINGS_LINK_MOBILE_UNPAIRED,
        SETTINGS_LINK_DISCONNECT,
        SETTINGS_LINK_STATUS_OFF, SETTINGS_LINK_STATUS_NO_DESKTOP,
        SETTINGS_LINK_STATUS_CONNECTED, SETTINGS_LINK_STATUS_UNREACHABLE,
        SETTINGS_OLLAMA_HEADER, SETTINGS_OLLAMA_DISABLED_BY_LINK, SETTINGS_OLLAMA_DESC, SETTINGS_OLLAMA_DESC_NOTE,
        SETTINGS_OLLAMA_SSL_LOCKED, SETTINGS_OLLAMA_SSL_LOCKED_BY_KEY, SETTINGS_OLLAMA_SSL,
        SETTINGS_OLLAMA_HTTP_WARNING,
        SETTINGS_OLLAMA_BASE_URL, SETTINGS_OLLAMA_HOST, SETTINGS_OLLAMA_PORT, SETTINGS_OLLAMA_OPENAI_HINT,
        SETTINGS_OLLAMA_TEST, SETTINGS_OLLAMA_CHAT_MODEL, SETTINGS_OLLAMA_VISION_MODEL,
        SETTINGS_OLLAMA_APIKEY_SET, SETTINGS_OLLAMA_APIKEY_REQUIRED, SETTINGS_OLLAMA_APIKEY_NONE,
        SETTINGS_OLLAMA_APIKEY_LABEL, SETTINGS_OLLAMA_APIKEY_LABEL_OPTIONAL,
        SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_REPLACE, SETTINGS_OLLAMA_APIKEY_PLACEHOLDER_PASTE,
        SETTINGS_OLLAMA_APIKEY_REQUIRES_SSL,
        SETTINGS_OLLAMA_SAVE_KEY, SETTINGS_OLLAMA_CLEAR_KEY,
        SETTINGS_OLLAMA_STATUS_CONNECTING, SETTINGS_OLLAMA_STATUS_CONNECTED,
        SETTINGS_OLLAMA_STATUS_NO_MODELS_OPENAI, SETTINGS_OLLAMA_STATUS_NO_MODELS,
        SETTINGS_OLLAMA_STATUS_FAILED, SETTINGS_OLLAMA_STATUS_OFF,
        SETTINGS_OLLAMA_STATUS_ACTIVE, SETTINGS_OLLAMA_STATUS_NOT_CONFIGURED,
        SETTINGS_OLLAMA_SERVER_TYPE_LABEL,
        SETTINGS_ABOUT_HEADER, SETTINGS_ABOUT_DESKTOP_AGENT, SETTINGS_ABOUT_LOCAL_AGENT,
        SETTINGS_ABOUT_BUILD_INFO,
        // ── Chat (UI) ──
        CHAT_EMPTY_TITLE, CHAT_EMPTY_BODY, CHAT_INPUT_HINT, CHAT_SEND,
        CHAT_CANCEL, CHAT_CANCELLING, CHAT_DISMISS, CHAT_OK,
        CHAT_ERROR_PREFIX, CHAT_THINKING, CHAT_FROM_CACHE,
        CHAT_SEARCHING, CHAT_RUNNING_JOB, CHAT_SEARCH_FAILED,
        CHAT_OVERFLOW_TITLE, CHAT_OVERFLOW_BODY, CHAT_OVERFLOW_CONTINUE, CHAT_OVERFLOW_START_NEW,
        CHAT_MEMORY_LIMIT_TITLE, CHAT_MEMORY_LIMIT_BODY,
        CHAT_MEMORY_PROMPT_SAVE_AS,
        CHAT_CATEGORY_PERSONAL_IDENTITY, CHAT_CATEGORY_PREFERENCE, CHAT_CATEGORY_PROFESSIONAL,
        CHAT_CATEGORY_INTEREST, CHAT_CATEGORY_RELATIONSHIP, CHAT_CATEGORY_TEMPORARY_CONTEXT,
        CHAT_SESSION_UNLOADED, CHAT_SESSION_DOWNLOADING, CHAT_SESSION_LOADING,
        CHAT_SESSION_LOADED_CPU, CHAT_SESSION_LOADED, CHAT_SESSION_FAILED,
        CHAT_SESSION_MOBILE_UNLOADED, CHAT_SESSION_MOBILE_DOWNLOADING, CHAT_SESSION_MOBILE_LOADING,
        CHAT_SESSION_MOBILE_LOADED_GPU, CHAT_SESSION_MOBILE_LOADED_CPU,
        CHAT_THERMAL_WARM, CHAT_THERMAL_BLOCK_TITLE, CHAT_THERMAL_BLOCK_BODY,
        CHAT_CD_NEW_CHAT, CHAT_CD_MYLIST, CHAT_CD_TIMERS, CHAT_CD_ALARMS,
        CHAT_CD_JOBS, CHAT_CD_SETTINGS, CHAT_CD_JUMP_TO_LATEST,
        CHAT_CD_ATTACHED_IMAGE, CHAT_CD_ATTACH_IMAGE, CHAT_CD_REMOVE_IMAGE,
        CHAT_CD_CLEAR_INPUT, CHAT_CD_DELETE_TURN,
        CHAT_DELETE_TURN_TITLE, CHAT_DELETE_TURN_BODY, CHAT_DELETE_TURN_CONFIRM, CHAT_DELETE_TURN_CANCEL,
        CHAT_CD_MIC_START, CHAT_CD_MIC_STOP, CHAT_CD_TTS_ENABLE, CHAT_CD_TTS_DISABLE,
        CHAT_CD_DISMISS_THERMAL,
        CHAT_CD_MEM_HEALTHY, CHAT_CD_MEM_CAUTION, CHAT_CD_MEM_LOW,
        CHAT_CD_LINK_CONNECTED, CHAT_CD_LINK_UNREACHABLE,
        // ── Onboarding (UI) ──
        ONBOARDING_NAV_CONTINUE, ONBOARDING_NAV_SKIP_SETTINGS,
        ONBOARDING_LANGUAGE_TITLE, ONBOARDING_LANGUAGE_BODY,
        // ── Onboarding — model download ──
        DOWNLOAD_TITLE, DOWNLOAD_INTRO, DOWNLOAD_MODELS_HEADER, DOWNLOAD_TOTAL,
        DOWNLOAD_SPEC_INCOMPLETE, DOWNLOAD_STATE_IDLE, DOWNLOAD_STATE_QUEUED,
        DOWNLOAD_STATE_STARTING, DOWNLOAD_STATE_COMPLETED, DOWNLOAD_PROGRESS, DOWNLOAD_FAILED,
        DOWNLOAD_ACTION_WIFI, DOWNLOAD_ACTION_CELLULAR, DOWNLOAD_ACTION_PAUSE,
        DOWNLOAD_ACTION_RETRY_WIFI, DOWNLOAD_ACTION_RETRY_CELLULAR,
        DOWNLOAD_ERROR_NETWORK, DOWNLOAD_ERROR_HTTP_CLIENT, DOWNLOAD_ERROR_HTTP_SERVER,
        DOWNLOAD_ERROR_STORAGE, DOWNLOAD_ERROR_CHECKSUM, DOWNLOAD_ERROR_MISCONFIGURED,
        // ── Memory (UI) ──
        MEMORY_TITLE, MEMORY_CONVERSATION_TITLE, MEMORY_EMPTY, MEMORY_CONVERSATION_EMPTY,
        MEMORY_LOADING, MEMORY_CREATION_TOGGLE, MEMORY_CLEAR_ALL, MEMORY_CLEAR_ALL_BODY,
        MEMORY_CLEAR_ALL_TITLE,
        MEMORY_EXPORT, MEMORY_IMPORT, MEMORY_TOAST_NOTHING_EXPORT,
        MEMORY_TOAST_EXPORTED, MEMORY_TOAST_IMPORTED, MEMORY_TOAST_IMPORTED_SKIPPED,
        MEMORY_DELETE_TITLE, MEMORY_DELETE_BODY, MEMORY_DELETE, MEMORY_CANCEL, MEMORY_OK,
        MEMORY_IMPORT_TITLE, MEMORY_IMPORT_BODY, MEMORY_CHOOSE_FILE,
        MEMORY_IMPORT_CAP_TITLE, MEMORY_IMPORT_CAP_BODY,
        MEMORY_CREATED, MEMORY_CREATED_EXPIRES, MEMORY_CD_MORE, MEMORY_CD_DELETE,
        MEMORY_CATEGORY_PERSONAL_IDENTITY, MEMORY_CATEGORY_PREFERENCE, MEMORY_CATEGORY_PROFESSIONAL,
        MEMORY_CATEGORY_INTEREST, MEMORY_CATEGORY_RELATIONSHIP, MEMORY_CATEGORY_TEMPORARY_CONTEXT,
        MEMORY_CATEGORY_SHORT_PERSONAL_IDENTITY, MEMORY_CATEGORY_SHORT_PREFERENCE,
        MEMORY_CATEGORY_SHORT_PROFESSIONAL, MEMORY_CATEGORY_SHORT_INTEREST,
        MEMORY_CATEGORY_SHORT_RELATIONSHIP, MEMORY_CATEGORY_SHORT_TEMPORARY_CONTEXT,
        // ── History (UI) ──
        HISTORY_TITLE, HISTORY_EMPTY, HISTORY_CAPACITY_FOOTNOTE,
        HISTORY_DELETE_TITLE, HISTORY_DELETE_BODY, HISTORY_DELETE, HISTORY_CANCEL, HISTORY_CD_DELETE,
        HISTORY_SEARCH_HINT, HISTORY_CD_SEARCH, HISTORY_CD_CLOSE_SEARCH, HISTORY_CD_CLEAR_SEARCH, HISTORY_SEARCH_EMPTY,
        // ── Clock (UI) ──
        CLOCK_UI_ALARMS_TITLE, CLOCK_UI_ALARMS_EMPTY, CLOCK_UI_ALARMS_EMPTY_HINT,
        CLOCK_UI_TIMERS_TITLE, CLOCK_UI_TIMERS_EMPTY, CLOCK_UI_TIMERS_EMPTY_HINT,
        CLOCK_UI_ONCE, CLOCK_UI_WEEKDAYS, CLOCK_UI_WEEKENDS, CLOCK_UI_EVERY_DAY,
        CLOCK_UI_OFF_SUFFIX, CLOCK_UI_EDIT, CLOCK_UI_CANCEL, CLOCK_UI_ADD,
        CLOCK_UI_NEW_ALARM, CLOCK_UI_EDIT_ALARM, CLOCK_UI_LABEL_OPTIONAL,
        CLOCK_UI_NEW_TIMER, CLOCK_UI_TIMER_DEFAULT_NAME,
        CLOCK_UI_EXTEND_1MIN, CLOCK_UI_EXTEND_5MIN,
        CLOCK_UI_DURATION_H, CLOCK_UI_DURATION_M, CLOCK_UI_DURATION_S, CLOCK_UI_START,
        CLOCK_UI_CD_NEW_ALARM, CLOCK_UI_CD_NEW_TIMER,
        CLOCK_UI_DAY_LETTER_SUN, CLOCK_UI_DAY_LETTER_MON, CLOCK_UI_DAY_LETTER_TUE,
        CLOCK_UI_DAY_LETTER_WED, CLOCK_UI_DAY_LETTER_THU, CLOCK_UI_DAY_LETTER_FRI,
        CLOCK_UI_DAY_LETTER_SAT,
        CLOCK_UI_DAY_SHORT_MON, CLOCK_UI_DAY_SHORT_TUE, CLOCK_UI_DAY_SHORT_WED,
        CLOCK_UI_DAY_SHORT_THU, CLOCK_UI_DAY_SHORT_FRI, CLOCK_UI_DAY_SHORT_SAT,
        CLOCK_UI_DAY_SHORT_SUN,
        // ── My List (UI) ──
        MYLIST_UI_TITLE, MYLIST_UI_EMPTY, MYLIST_UI_EMPTY_HINT, MYLIST_UI_CLEAR_DONE,
        MYLIST_UI_DELETE_TITLE, MYLIST_UI_DELETE_BODY, MYLIST_UI_DELETE, MYLIST_UI_CANCEL,
        MYLIST_UI_NEW_ITEM, MYLIST_UI_EDIT_ITEM, MYLIST_UI_TITLE_LABEL, MYLIST_UI_PRIORITY,
        MYLIST_UI_DUE_PREFIX, MYLIST_UI_SET, MYLIST_UI_CHANGE, MYLIST_UI_NOTES_OPTIONAL,
        MYLIST_UI_ADD, MYLIST_UI_OK, MYLIST_UI_CD_ADD, MYLIST_UI_CD_EDIT, MYLIST_UI_CD_DELETE,
        // ── Jobs (UI) ──
        JOBS_TITLE, JOBS_CD_ADD, JOBS_DELETE_TITLE, JOBS_DELETE_BODY, JOBS_DELETE, JOBS_CANCEL,
        JOBS_SYNCED, JOBS_NEVER_SYNCED,
        JOBS_STATUS_ONLINE, JOBS_STATUS_OFFLINE, JOBS_STATUS_NOT_LINKED,
        JOBS_EMPTY, JOBS_EMPTY_HINT_ADMIN, JOBS_EMPTY_HINT_REMOTE,
        JOBS_RUNNING, JOBS_STATUS_RUNNING, JOBS_STATUS_SUCCEEDED, JOBS_STATUS_FAILED, JOBS_STATUS_CANCELLED,
        JOBS_VIEW_CONVERSATION, JOBS_CD_RUN_NOW, JOBS_CD_CANCEL_RUN, JOBS_CD_EDIT, JOBS_CD_DELETE,
        JOBS_SCHED_DAILY_AT, JOBS_SCHED_AT, JOBS_SCHED_CRON_RAW, JOBS_REPEAT,
        JOBS_SCHED_ONCE_ON, JOBS_ONCE,
        JOBS_DAY_SHORT_SUN, JOBS_DAY_SHORT_MON, JOBS_DAY_SHORT_TUE, JOBS_DAY_SHORT_WED,
        JOBS_DAY_SHORT_THU, JOBS_DAY_SHORT_FRI, JOBS_DAY_SHORT_SAT,
        JOBS_DAY_LETTER_SUN, JOBS_DAY_LETTER_MON, JOBS_DAY_LETTER_TUE, JOBS_DAY_LETTER_WED,
        JOBS_DAY_LETTER_THU, JOBS_DAY_LETTER_FRI, JOBS_DAY_LETTER_SAT,
        JOBS_FORM_NEW, JOBS_FORM_EDIT, JOBS_FORM_CREATE, JOBS_FORM_NAME, JOBS_FORM_LOCATION,
        JOBS_FORM_CHOOSE_TOOLTIP, JOBS_FORM_CHOOSE_JOB, JOBS_FORM_KEYWORDS, JOBS_FORM_SCHEDULE,
        JOBS_FORM_AM, JOBS_FORM_PM, JOBS_FORM_HOUR, JOBS_FORM_MIN,
        JOBS_FORM_RUNS_DAILY_AT, JOBS_FORM_RUNS_DAYS_AT, JOBS_FORM_RUN_ONCE_AFTER,
        JOBS_FORM_DUR_H, JOBS_FORM_DUR_M, JOBS_FORM_DUR_S,
        JOBS_CHOOSE_TITLE, JOBS_CHOOSE_LOADING, JOBS_CHOOSE_EMPTY, JOBS_CHOOSE_UNAVAILABLE_OS,
        JOBS_INIT_SETTING_UP, JOBS_INIT_AWAIT_USER, JOBS_INIT_FAILED_TITLE,
        JOBS_INIT_INTRO, JOBS_INIT_NONE, JOBS_INIT_APPROVE,
        // ── Search sources (UI) ──
        SEARCH_SOURCES_TITLE, SEARCH_SOURCES_COUNTRY_LABEL,
        SEARCH_SOURCES_GENERAL, SEARCH_SOURCES_NEWS, SEARCH_SOURCES_WEATHER,
        SEARCH_SOURCES_SPORTS, SEARCH_SOURCES_FINANCE,
        SEARCH_SOURCES_NONE_CONFIGURED, SEARCH_SOURCES_ADD,
        SEARCH_SOURCES_CD_EDIT, SEARCH_SOURCES_CD_REMOVE,
        SEARCH_SOURCES_DIALOG_TITLE_ADD, SEARCH_SOURCES_DIALOG_TITLE_EDIT,
        SEARCH_SOURCES_DIALOG_ADD, SEARCH_SOURCES_DIALOG_CANCEL,
        SEARCH_SOURCES_DIALOG_DOMAIN, SEARCH_SOURCES_DIALOG_DISPLAY_NAME,
        SEARCH_SOURCES_DIALOG_KIND, SEARCH_SOURCES_DIALOG_ENDPOINT,
        SEARCH_SOURCES_DIALOG_TEMPLATE_HINT,
        // ── Relative-time / date formatting (UI) ──
        FMT_NOW, FMT_AGO, FMT_IN, FMT_MONTHS, FMT_DATE_SHORT, FMT_DATE_YEAR,
        // ── Notifications ──
        NOTIF_JOB_FINISHED, NOTIF_JOB_FAILED,
        NOTIF_TASK_COMPLETE, NOTIF_TASK_CANCELLED, NOTIF_TASK_FAILED,
        NOTIF_MODEL_DOWNLOADING, NOTIF_MODEL_STARTING, NOTIF_MODEL_READY,
        NOTIF_MODEL_COMPLETE, NOTIF_MODEL_FAILED,
        NOTIF_MODELS_READY, NOTIF_MODELS_ALL_DONE, NOTIF_MODELS_INCOMPLETE,
        NOTIF_MODELS_FAILED_COUNT,
        // ── Data-display names ──
        DATA_COUNTRY_CA, DATA_COUNTRY_US, DATA_COUNTRY_GB, DATA_COUNTRY_AU,
        // ── Desktop sections (UI) ──
        DESKTOP_VOICE_SECTION_HEADER, DESKTOP_VOICE_DESCRIPTION,
        DESKTOP_VOICE_ENGINE_PIPER, DESKTOP_VOICE_SYSTEM_DEFAULT,
        DESKTOP_VOICE_ENGINE_LABEL, DESKTOP_VOICE_VOICE_LABEL,
        DESKTOP_VOICE_SPEECH_RATE, DESKTOP_VOICE_RATE_NORMAL,
        DESKTOP_VOICE_RATE_SLOWER, DESKTOP_VOICE_RATE_FASTER,
        DESKTOP_VOICE_TEST_VOICE, DESKTOP_VOICE_TEST_UTTERANCE,
        DESKTOP_VOICE_DOWNLOADING, DESKTOP_VOICE_READY, DESKTOP_VOICE_FAILED,
        DESKTOP_VOICE_UNAVAILABLE, DESKTOP_VOICE_IDLE,
        DESKTOP_VOICE_FILTER, DESKTOP_VOICE_SHOWING,
        DESKTOP_GPU_TITLE, DESKTOP_GPU_DESCRIPTION, DESKTOP_GPU_DETECT_DEVICES,
        DESKTOP_GPU_AUTO_ALL, DESKTOP_GPU_DEVICE_PREFIX, DESKTOP_GPU_PINNED,
        DESKTOP_GPU_DETECTION_FAILED, DESKTOP_GPU_NO_DEVICES,
        DESKTOP_LINK_SUBSCRIBE_PROMPT, DESKTOP_LINK_QR_CD,
        DESKTOP_LINK_SCAN_INSTRUCTIONS, DESKTOP_LINK_PAIR_NOW, DESKTOP_LINK_CODE_EXPIRES,
        DESKTOP_LINK_QR_WARNING,
        DESKTOP_BACKUP_FILTER_DESC,
    )
}
