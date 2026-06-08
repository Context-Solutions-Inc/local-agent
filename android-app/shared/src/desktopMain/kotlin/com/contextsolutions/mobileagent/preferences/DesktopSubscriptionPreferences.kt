package com.contextsolutions.mobileagent.preferences

import com.contextsolutions.mobileagent.platform.DesktopJsonStore
import com.contextsolutions.mobileagent.subscription.SubscriptionPreferences
import com.contextsolutions.mobileagent.subscription.SubscriptionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Desktop [SubscriptionPreferences] (PR #74) — the paid "anywhere access" record,
 * backed by a [DesktopJsonStore] file (`subscription_prefs.json`). Stores only the
 * non-secret Secure Gateway identifiers + last-known status as one JSON blob; the
 * account *secret* is in `SecureStorage` (RELAY_ACCOUNT_SECRET), never here.
 * Mirrors [DesktopOllamaPreferences].
 */
class DesktopSubscriptionPreferences(private val store: DesktopJsonStore) : SubscriptionPreferences {

    private val json = Json { ignoreUnknownKeys = true }
    private val flow = MutableStateFlow(load())

    private fun load(): SubscriptionState = store.getString(KEY_STATE)
        ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        ?.toState()
        ?: SubscriptionState.EMPTY

    override fun state(): SubscriptionState = flow.value

    override fun stateFlow(): Flow<SubscriptionState> = flow.asStateFlow()

    override fun update(state: SubscriptionState) {
        if (flow.value == state) return // idempotent
        flow.value = state
        store.putString(KEY_STATE, json.encodeToString(Stored.serializer(), Stored.from(state)))
    }

    override fun clear() {
        flow.value = SubscriptionState.EMPTY
        store.remove(KEY_STATE)
    }

    // Serializable mirror — keeps SubscriptionState (with computed props) free of
    // serialization annotations and stable across field additions.
    @kotlinx.serialization.Serializable
    private data class Stored(
        val accountId: String = "",
        val subscriptionId: String = "",
        val licenseId: String = "",
        val status: String = SubscriptionState.STATUS_NONE,
        val currentPeriodEndEpochSec: Long = 0L,
    ) {
        fun toState() = SubscriptionState(accountId, subscriptionId, licenseId, status, currentPeriodEndEpochSec)

        companion object {
            fun from(s: SubscriptionState) =
                Stored(s.accountId, s.subscriptionId, s.licenseId, s.status, s.currentPeriodEndEpochSec)
        }
    }

    private companion object {
        const val KEY_STATE = "subscription"
    }
}
