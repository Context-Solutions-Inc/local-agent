package com.contextsolutions.mobileagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextsolutions.mobileagent.inference.DesktopLinkClient
import com.contextsolutions.mobileagent.link.transport.LinkAccessMode
import com.contextsolutions.mobileagent.link.transport.RelayQrPayload
import com.contextsolutions.mobileagent.inference.DesktopLinkStatus
import com.contextsolutions.mobileagent.inference.DesktopLinkStatusProvider
import com.contextsolutions.mobileagent.inference.OllamaClient
import com.contextsolutions.mobileagent.inference.OllamaModel
import com.contextsolutions.mobileagent.link.DesktopLinkConnectionStatus
import com.contextsolutions.mobileagent.link.MobileLinkKind
import com.contextsolutions.mobileagent.link.DesktopLinkQrProvider
import com.contextsolutions.mobileagent.link.LinkPairingPayload
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import com.contextsolutions.mobileagent.preferences.DesktopLinkConfig
import com.contextsolutions.mobileagent.preferences.DesktopLinkPreferences
import com.contextsolutions.mobileagent.language.LanguagePreferences
import com.contextsolutions.mobileagent.language.PreferredLanguage
import com.contextsolutions.mobileagent.memory.MemoryPreferences
import com.contextsolutions.mobileagent.memory.MemoryStore
import com.contextsolutions.mobileagent.observability.SafeCrashReporter
import com.contextsolutions.mobileagent.platform.AppBuildConfig
import com.contextsolutions.mobileagent.platform.SecureStorage
import com.contextsolutions.mobileagent.platform.SecureStorageKeys
import com.contextsolutions.mobileagent.preferences.OllamaConfig
import com.contextsolutions.mobileagent.preferences.RemoteServerType
import com.contextsolutions.mobileagent.preferences.OllamaPreferences
import com.contextsolutions.mobileagent.search.SearchCacheDao
import com.contextsolutions.mobileagent.subscription.SubscriptionState
import com.contextsolutions.mobileagent.subscription.RelayDisconnector
import com.contextsolutions.mobileagent.subscription.SubscriptionUiController
import com.contextsolutions.mobileagent.telemetry.TelemetryConsentManager
import com.contextsolutions.mobileagent.telemetry.TelemetryUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs the M2 settings screen: Brave key, search toggle, cache clear.
 *
 * The Brave key lives in [SecureStorage] (EncryptedSharedPreferences); this VM
 * never holds the key in memory beyond what the user is currently typing — the
 * UI mask + immediate save-and-discard pattern keeps the key out of the
 * Compose state tree.
 */
class SettingsViewModel(
    private val secureStorage: SecureStorage,
    private val cache: SearchCacheDao,
    private val telemetryConsent: TelemetryConsentManager,
    private val crashReporter: SafeCrashReporter,
    private val languagePreferences: LanguagePreferences,
    private val telemetryUploader: TelemetryUploader,
    private val buildConfig: AppBuildConfig,
    private val memoryStore: MemoryStore,
    private val memoryPreferences: MemoryPreferences,
    private val ollamaPreferences: OllamaPreferences,
    private val ollamaClient: OllamaClient,
    private val desktopLinkPreferences: DesktopLinkPreferences,
    private val desktopLinkClient: DesktopLinkClient,
    private val desktopLinkStatusProvider: DesktopLinkStatusProvider,
    private val desktopLinkQrProvider: DesktopLinkQrProvider,
    private val desktopLinkConnectionStatus: DesktopLinkConnectionStatus,
    private val subscription: SubscriptionUiController,
    private val relayDisconnector: RelayDisconnector,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Mirror the consent toggle into UI state. Phase E onboarding's
        // consent screen writes via the same TelemetryConsentManager;
        // observing the flow keeps this Settings surface in sync without
        // a manual refresh on return-to-Settings.
        telemetryConsent.enabledFlow()
            .onEach { enabled -> _state.update { it.copy(telemetryEnabled = enabled) } }
            .launchIn(viewModelScope)
        // PR #10 — mirror the preferred-language preference. Settings is
        // the only surface that writes to this, but the same Flow pattern
        // keeps the UI consistent if a future flow (e.g. an onboarding
        // step) also writes.
        languagePreferences.preferredLanguageFlow()
            .onEach { lang -> _state.update { it.copy(preferredLanguage = lang) } }
            .launchIn(viewModelScope)
        // PR #56 — mirror the Ollama server config so the Settings section
        // reflects an external clear/reload without a manual refresh.
        ollamaPreferences.configFlow()
            .onEach { cfg -> _state.update { it.copy(ollamaConfig = cfg) } }
            .launchIn(viewModelScope)
        // PR #57 — mirror the desktop-link config, live status, and (desktop) the
        // pairing-QR payload so the Settings section reflects pairing/unpairing,
        // reachability, and greys out Ollama when the link is active.
        desktopLinkPreferences.configFlow()
            .onEach { cfg -> _state.update { it.copy(desktopLinkConfig = cfg) } }
            .launchIn(viewModelScope)
        desktopLinkStatusProvider.status
            .onEach { st -> _state.update { it.copy(desktopLinkStatus = st) } }
            .launchIn(viewModelScope)
        desktopLinkQrProvider.qrPayload
            .onEach { p -> _state.update { it.copy(desktopLinkQrPayload = p) } }
            .launchIn(viewModelScope)
        desktopLinkConnectionStatus.mobileConnected
            .onEach { c -> _state.update { it.copy(mobileConnected = c) } }
            .launchIn(viewModelScope)
        desktopLinkConnectionStatus.connectionKind
            .onEach { k -> _state.update { it.copy(mobileConnectionKind = k) } }
            .launchIn(viewModelScope)
        // PR #74 — mirror the paid "anywhere access" subscription state so the
        // desktop "Mobile Agent Connection" section flips its copy + upgrade link
        // when a subscription becomes active (or is revoked).
        subscription.stateFlow()
            .onEach { s -> _state.update { it.copy(subscription = s) } }
            .launchIn(viewModelScope)
    }

    /** PR #74 — start (or resume) the Stripe Checkout flow. Desktop-only. */
    fun upgradeToAnywhereAccess() = subscription.onUpgrade()

    /** PR #74 — open the Stripe Customer Portal / account page. Desktop-only. */
    fun openSubscriptionSettings() = subscription.onManage()

    /** Whether the upgrade UI should show (desktop with a configured gateway). */
    val subscriptionAvailable: Boolean get() = subscription.available

    /** Toggle the "Desktop Agent Connection". Keeps any paired peer + token. */
    fun setAutoDesktopLinkEnabled(enabled: Boolean) {
        desktopLinkPreferences.setConfig(desktopLinkPreferences.config().copy(enabled = enabled))
    }

    /**
     * Apply a scanned desktop QR: store the peer endpoint + token, enable the
     * link, then POST `/pair` so the desktop records this phone. Returns true if
     * the QR parsed (pairing reachability is reflected later by the status dot).
     */
    fun applyScannedLink(rawQr: String): Boolean {
        // TESTING diagnostics (revert with the relay logging). Sanitized — never log the
        // raw QR or the account secret (it sits last in the relay JSON, after `endpoints`).
        val head = rawQr.trim().take(40).replace("\n", " ")
        println("[Relay/scan] applyScannedLink: ${rawQr.length} chars; head='$head'")
        // LAN `magent://link` QR (PR #57).
        LinkPairingPayload.parse(rawQr)?.let { payload ->
            println("[Relay/scan] -> matched LAN magent:// host=${payload.host}:${payload.port}")
            val self = desktopLinkPreferences.config().selfDeviceId
            desktopLinkPreferences.setConfig(
                desktopLinkPreferences.config().copy(
                    enabled = true,
                    accessMode = LinkAccessMode.LAN,
                    relayQrJson = "",
                    peerHost = payload.host,
                    peerPort = payload.port,
                    pairingToken = payload.token,
                    pairedDeviceId = payload.deviceId,
                ),
            )
            viewModelScope.launch {
                val baseUrl = "http://${payload.host}:${payload.port}"
                withContext(Dispatchers.IO) { desktopLinkClient.pair(baseUrl, payload.token, self) }
            }
            return true
        }
        // Relay QR (anywhere access). The phone has no subscription of its own —
        // the QR carries the desktop's account secret, stored in SecureStorage. The
        // transport provider pairs + connects the relay in the background.
        RelayQrPayload.parseOrNull(rawQr)?.let { relay ->
            println("[Relay/scan] -> matched RELAY v=${relay.v} relay='${relay.endpoints["relay"]}' auth='${relay.endpoints["auth"]}' token=${relay.pairingToken.take(6)}… secret=${if (relay.accountSecret.isBlank()) "MISSING" else "present"}")
            if (relay.accountSecret.isNotBlank()) {
                secureStorage.put(SecureStorageKeys.RELAY_ACCOUNT_SECRET, relay.accountSecret)
            }
            desktopLinkPreferences.setConfig(
                desktopLinkPreferences.config().copy(
                    enabled = true,
                    accessMode = LinkAccessMode.RELAY,
                    relayQrJson = rawQr,
                    pairedDeviceId = relay.desktopDeviceId,
                    // Clear stale LAN endpoint so the LAN path can't be picked.
                    peerHost = "",
                    peerPort = null,
                    pairingToken = "",
                ),
            )
            return true
        }
        println("[Relay/scan] -> UNRECOGNIZED: not a LAN magent:// URI and not a valid relay QR " +
            "(relay needs v>=1 + pairing_token + endpoints.relay). Persisted nothing.")
        return false
    }

    /** Forget the paired desktop (and disable the link). Mobile side. */
    fun unpairDesktop() {
        // Relay (gateway) link: revoke the pairing at the gateway first (frees the slot +
        // cuts the desktop's view of this phone) WHILE the MobileClient is still alive,
        // then clear local state. LAN: just forget locally.
        if (desktopLinkPreferences.config().accessMode == LinkAccessMode.RELAY) {
            viewModelScope.launch {
                runCatching { relayDisconnector.disconnect() }
                desktopLinkPreferences.clear()
                secureStorage.remove(SecureStorageKeys.RELAY_ACCOUNT_SECRET)
                // Drop the saved pairing too: its pairId is now revoked at the gateway, and a
                // fresh scan brings a new single-use token anyway.
                secureStorage.remove(SecureStorageKeys.RELAY_PAIRING_STATE)
            }
            return
        }
        desktopLinkPreferences.clear()
        // Drop the relay account secret the QR conveyed — don't leave it on the phone.
        secureStorage.remove(SecureStorageKeys.RELAY_ACCOUNT_SECRET)
        secureStorage.remove(SecureStorageKeys.RELAY_PAIRING_STATE)
    }

    /**
     * Desktop side: disconnect the connected phone. For a relay connection, revoke the
     * pairing at the gateway (the phone drops + the pair slot frees) and re-mint a fresh
     * QR via [relayDisconnector]. For LAN, rotate the pairing token and forget the phone —
     * the old token stops authorizing immediately and the QR republishes for a re-scan.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun disconnectMobileDevice() {
        if (desktopLinkConnectionStatus.connectionKind.value == MobileLinkKind.RELAY) {
            viewModelScope.launch { relayDisconnector.disconnect() }
            return
        }
        desktopLinkPreferences.setConfig(
            desktopLinkPreferences.config().copy(
                pairingToken = Uuid.random().toString(),
                pairedDeviceId = "",
            ),
        )
    }

    fun saveBraveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        } else {
            secureStorage.put(SecureStorageKeys.BRAVE_API_KEY, trimmed)
        }
        _state.update { it.copy(hasUserKey = trimmed.isNotEmpty(), keyJustSaved = true) }
    }

    fun clearBraveKey() {
        secureStorage.remove(SecureStorageKeys.BRAVE_API_KEY)
        _state.update { it.copy(hasUserKey = false, keyJustSaved = false) }
    }

    fun acknowledgeKeySaved() {
        _state.update { it.copy(keyJustSaved = false) }
    }

    fun saveHfAuthToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.HF_AUTH_TOKEN)
        } else {
            secureStorage.put(SecureStorageKeys.HF_AUTH_TOKEN, trimmed)
        }
        _state.update {
            it.copy(hasUserHfToken = trimmed.isNotEmpty(), hfTokenJustSaved = true)
        }
    }

    fun clearHfAuthToken() {
        secureStorage.remove(SecureStorageKeys.HF_AUTH_TOKEN)
        _state.update { it.copy(hasUserHfToken = false, hfTokenJustSaved = false) }
    }

    fun acknowledgeHfTokenSaved() {
        _state.update { it.copy(hfTokenJustSaved = false) }
    }

    fun setSearchEnabled(enabled: Boolean) {
        secureStorage.put(SecureStorageKeys.SEARCH_ENABLED, if (enabled) "true" else "false")
        _state.update { it.copy(searchEnabled = enabled) }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            cache.clear()
            val count = cache.count()
            _state.update { it.copy(cacheCount = count, cacheJustCleared = true) }
        }
    }

    fun acknowledgeCacheCleared() {
        _state.update { it.copy(cacheJustCleared = false) }
    }

    fun setTelemetryEnabled(enabled: Boolean) {
        telemetryConsent.setEnabled(enabled)
        // The Application-level Flow observer (MobileAgentApplication) also
        // toggles FirebaseAnalytics.setAnalyticsCollectionEnabled in response.
    }

    /**
     * PR #10 — persist the user's preferred response language. Picked up
     * on the next chat turn (ChatViewModel reads `preferredLanguage()` at
     * send-time); no need to interrupt an in-flight turn.
     */
    fun setPreferredLanguage(language: PreferredLanguage) {
        languagePreferences.setPreferredLanguage(language)
    }

    /**
     * Debug-only — bypass the 24h periodic schedule and fire one telemetry
     * upload immediately. The button on [SettingsScreen] that calls this
     * is gated behind `BuildConfig.DEBUG`; the uploader itself still
     * checks consent, so this never sends data when the user is opted out.
     * Outcome appears in `logcat -s TelemetryWorker:I`.
     */
    fun triggerTelemetryUploadNow() {
        viewModelScope.launch(Dispatchers.IO) { telemetryUploader.upload() }
    }

    /**
     * Re-load the small memory summary shown on the "Memory" row (count +
     * creation-toggle state). Called from the screen's entry effect — this
     * replaces the cross-VM dependency on `MemoryViewModel` that the screen
     * carried before it moved into shared `:ui` (Phase 9).
     */
    fun refreshMemorySummary() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { memoryStore.listAll().size }
            _state.update {
                it.copy(
                    memoryCount = count,
                    memoryCreationEnabled = memoryPreferences.creationEnabled(),
                )
            }
        }
    }

    /**
     * Debug-only — record a non-fatal exception that contains a leak
     * marker string in its message. Verifies that
     * [SafeCrashReporter.recordException] runs the throwable through
     * [com.contextsolutions.mobileagent.observability.ContentRedactor]
     * before forwarding to Crashlytics. The leaked Crashlytics dashboard
     * entry should show the redacted form (`Bearer <redacted>`), NOT
     * the raw token. The user must be opted in for this to surface;
     * Crashlytics collection is gated by the consent toggle.
     */
    fun triggerCrashRedactionTest() {
        crashReporter.recordException(
            RuntimeException(
                "telemetry leak test — Authorization: Bearer test_secret_12345 should be redacted",
            ),
        )
        // Force-flush so the report ships immediately. Without this,
        // Crashlytics queues non-fatals until the next app launch —
        // nothing appears in the dashboard for the developer's
        // verification flow.
        crashReporter.flushPending()
    }

    /**
     * Debug-only — record a breadcrumb that contains a leak marker.
     * Same redaction guarantee as [triggerCrashRedactionTest]: the
     * breadcrumb should appear scrubbed in the dashboard.
     *
     * Note: breadcrumbs ([SafeCrashReporter.log]) only appear in the
     * dashboard ATTACHED TO a recorded exception. To verify breadcrumb
     * redaction end-to-end, tap this button then immediately tap "Test
     * crash redaction" — the breadcrumb will appear in that crash's
     * Logs tab.
     */
    fun triggerBreadcrumbRedactionTest() {
        crashReporter.log(
            "breadcrumb leak test — X-Subscription-Token: BSA-test-key-12345 should be redacted",
        )
    }

    /**
     * PR #56 — probe an Ollama server at [host]:[port] and populate the model
     * dropdowns. Drives the "Test connection" button; an empty result (or any
     * error) reads as Failed. Does not persist anything — the user picks models
     * then taps Save.
     */
    fun testOllama(host: String, port: String, serverType: RemoteServerType, useSsl: Boolean) {
        val baseUrl = OllamaConfig(
            host = host.trim(),
            port = port.trim().toIntOrNull(),
            serverType = serverType,
            useSsl = useSsl,
        ).baseUrl()
        if (baseUrl == null) {
            _state.update { it.copy(ollamaTestStatus = OllamaTestStatus.Failed, ollamaModels = emptyList()) }
            return
        }
        _state.update { it.copy(ollamaTestStatus = OllamaTestStatus.Testing) }
        viewModelScope.launch {
            // Health (HTTP 200 on the model-list path) and model parsing are
            // distinct failures: an unreachable host vs. a reachable host whose
            // body isn't the expected model list (typically a wrong Base URL path
            // on an OpenAI-compatible server). Surface them separately.
            val reachable = withContext(Dispatchers.IO) { ollamaClient.health(baseUrl, serverType) }
            if (!reachable) {
                _state.update { it.copy(ollamaTestStatus = OllamaTestStatus.Failed, ollamaModels = emptyList()) }
                return@launch
            }
            val models = withContext(Dispatchers.IO) { ollamaClient.listModels(baseUrl, serverType) }
            _state.update {
                it.copy(
                    ollamaModels = models,
                    ollamaTestStatus = if (models.isEmpty()) OllamaTestStatus.NoModels else OllamaTestStatus.Connected,
                )
            }
        }
    }

    /**
     * PR #56 — persist the remote Ollama server + selected models. Once
     * [OllamaConfig.isConfigured], the routing engine serves chat from this
     * server instead of the on-device model. A blank [visionModel] means image
     * turns reuse [chatModel].
     */
    fun saveOllama(
        host: String,
        port: String,
        chatModel: String,
        visionModel: String,
        serverType: RemoteServerType,
        useSsl: Boolean,
    ) {
        val config = OllamaConfig(
            host = host.trim(),
            port = port.trim().toIntOrNull(),
            chatModel = chatModel.trim(),
            visionModel = visionModel.trim(),
            serverType = serverType,
            useSsl = useSsl,
        )
        ollamaPreferences.setConfig(config)
        _state.update { it.copy(ollamaConfig = config, ollamaJustSaved = true) }
    }

    /**
     * PR #73 — on/off switch for the remote connection. Flips [OllamaConfig.enabled]
     * on the *persisted* config (keeping the saved host/model/key intact) so the
     * routing engine re-decides on the next turn (via `configFlow`). Off → chat
     * returns to the on-device model without losing the server details.
     */
    fun setOllamaEnabled(enabled: Boolean) {
        val config = ollamaPreferences.config().copy(enabled = enabled)
        ollamaPreferences.setConfig(config)
        _state.update { it.copy(ollamaConfig = config) }
    }

    /** PR #56 — clear the remote server (reverts chat to the on-device model). */
    fun clearOllama() {
        ollamaPreferences.clear()
        _state.update {
            it.copy(
                ollamaConfig = OllamaConfig.EMPTY,
                ollamaModels = emptyList(),
                ollamaTestStatus = OllamaTestStatus.Idle,
            )
        }
    }

    fun acknowledgeOllamaSaved() {
        _state.update { it.copy(ollamaJustSaved = false) }
    }

    /**
     * PR #73 — clear a prior probe's models/status. Called when the user switches
     * the server type (Ollama ↔ OpenAI-compatible) or toggles SSL, since the
     * model list + reachability are backend-specific and a stale list would
     * mislead.
     */
    fun resetOllamaProbe() {
        _state.update { it.copy(ollamaModels = emptyList(), ollamaTestStatus = OllamaTestStatus.Idle) }
    }

    /**
     * PR #58 — persist the optional outbound API key for the remote chat LLM.
     * Stored encrypted in [SecureStorage] (same BYOK pattern as the Brave key);
     * read per-request by [OllamaInferenceEngine]/[OllamaClient], so a change
     * applies on the next turn. Blank clears it (reverts to no auth header).
     */
    fun saveOllamaApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secureStorage.remove(SecureStorageKeys.OLLAMA_API_KEY)
        } else {
            secureStorage.put(SecureStorageKeys.OLLAMA_API_KEY, trimmed)
        }
        _state.update { it.copy(hasOllamaApiKey = trimmed.isNotEmpty(), ollamaApiKeyJustSaved = true) }
    }

    fun clearOllamaApiKey() {
        secureStorage.remove(SecureStorageKeys.OLLAMA_API_KEY)
        _state.update { it.copy(hasOllamaApiKey = false, ollamaApiKeyJustSaved = false) }
    }

    fun acknowledgeOllamaApiKeySaved() {
        _state.update { it.copy(ollamaApiKeyJustSaved = false) }
    }

    private fun initialState(): SettingsUiState {
        val hasUser = secureStorage.contains(SecureStorageKeys.BRAVE_API_KEY) &&
            !secureStorage.get(SecureStorageKeys.BRAVE_API_KEY).isNullOrBlank()
        val hasUserHf = secureStorage.contains(SecureStorageKeys.HF_AUTH_TOKEN) &&
            !secureStorage.get(SecureStorageKeys.HF_AUTH_TOKEN).isNullOrBlank()
        val hasOllamaKey = secureStorage.contains(SecureStorageKeys.OLLAMA_API_KEY) &&
            !secureStorage.get(SecureStorageKeys.OLLAMA_API_KEY).isNullOrBlank()
        val searchEnabled = secureStorage.get(SecureStorageKeys.SEARCH_ENABLED) != "false"
        return SettingsUiState(
            hasUserKey = hasUser,
            hasDevKey = buildConfig.hasBraveDevKey,
            hasUserHfToken = hasUserHf,
            hasDevHfToken = buildConfig.hasHfDevToken,
            searchEnabled = searchEnabled,
            cacheCount = -1L,
            telemetryEnabled = telemetryConsent.enabled(),
            preferredLanguage = languagePreferences.preferredLanguage(),
            isDebugBuild = buildConfig.isDebug,
            memoryCreationEnabled = memoryPreferences.creationEnabled(),
            ollamaConfig = ollamaPreferences.config(),
            hasOllamaApiKey = hasOllamaKey,
            desktopLinkConfig = desktopLinkPreferences.config(),
            desktopLinkStatus = desktopLinkStatusProvider.status.value,
            desktopLinkQrPayload = desktopLinkQrProvider.qrPayload.value,
            mobileConnected = desktopLinkConnectionStatus.mobileConnected.value,
            mobileConnectionKind = desktopLinkConnectionStatus.connectionKind.value,
        ).also {
            // Load cache count off the main thread.
            viewModelScope.launch(Dispatchers.IO) {
                val count = withContext(Dispatchers.IO) { cache.count() }
                _state.update { st -> st.copy(cacheCount = count) }
            }
        }
    }
}

data class SettingsUiState(
    val hasUserKey: Boolean,
    val hasDevKey: Boolean,
    val hasUserHfToken: Boolean,
    val hasDevHfToken: Boolean,
    val searchEnabled: Boolean,
    /** -1 = not yet loaded. */
    val cacheCount: Long,
    val telemetryEnabled: Boolean = false,
    val preferredLanguage: PreferredLanguage = PreferredLanguage.DEFAULT,
    val keyJustSaved: Boolean = false,
    val hfTokenJustSaved: Boolean = false,
    val cacheJustCleared: Boolean = false,
    /** True for a debuggable build — gates debug-only affordances on the screen. */
    val isDebugBuild: Boolean = false,
    /** Memory summary shown on the "Memory" row. -1 = not yet loaded. */
    val memoryCount: Int = -1,
    val memoryCreationEnabled: Boolean = false,
    /** PR #56 — remote Ollama server config (EMPTY = use the on-device model). */
    val ollamaConfig: OllamaConfig = OllamaConfig.EMPTY,
    /** Models discovered by the last "Test connection" (drives the dropdowns). */
    val ollamaModels: List<OllamaModel> = emptyList(),
    val ollamaTestStatus: OllamaTestStatus = OllamaTestStatus.Idle,
    val ollamaJustSaved: Boolean = false,
    /** PR #58 — whether an optional outbound API key is set (never holds the key). */
    val hasOllamaApiKey: Boolean = false,
    val ollamaApiKeyJustSaved: Boolean = false,
    /** PR #57 — mobile↔desktop link config (enabled + paired peer). */
    val desktopLinkConfig: DesktopLinkConfig = DesktopLinkConfig.EMPTY,
    /** Live link reachability for the section status + the Ollama grey-out. */
    val desktopLinkStatus: DesktopLinkStatus = DesktopLinkStatus.DISABLED,
    /** Desktop-only: the pairing-QR payload to render (null on mobile). */
    val desktopLinkQrPayload: String? = null,
    /** Desktop-only: whether a paired phone is currently connected to this desktop. */
    val mobileConnected: Boolean = false,
    /** Desktop-only: which transport the connected phone is on (LAN vs gateway/relay). */
    val mobileConnectionKind: MobileLinkKind = MobileLinkKind.NONE,
    /** PR #74 — paid "anywhere access" subscription state (desktop; EMPTY on mobile). */
    val subscription: SubscriptionState = SubscriptionState.EMPTY,
) {
    /**
     * The link is *active* (routing chat to the desktop) only when enabled, paired,
     * AND reachable. While active the Ollama server fields are greyed out; when the
     * link is down, routing falls back through Ollama, so its fields stay editable.
     */
    val desktopLinkActive: Boolean
        get() = desktopLinkConfig.isLinkConfigured && desktopLinkStatus == DesktopLinkStatus.UP
}

/** Outcome of the Settings "Test connection" probe against an Ollama server. */
enum class OllamaTestStatus {
    Idle,
    Testing,
    Connected,

    /** Reached the server (HTTP 200) but it returned no parseable models — usually a
     * wrong Base URL path for an OpenAI-compatible server (PR #73). */
    NoModels,
    Failed,
}
