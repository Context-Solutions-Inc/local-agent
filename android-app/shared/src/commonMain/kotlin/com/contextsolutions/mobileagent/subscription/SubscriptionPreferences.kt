package com.contextsolutions.mobileagent.subscription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Paid "anywhere access" subscription state (PR #74). The desktop stores the
 * **non-secret** Secure Gateway identifiers here (account/subscription/license id
 * + last-known status); the account *secret* lives in `SecureStorage`
 * ([com.contextsolutions.mobileagent.platform.SecureStorageKeys.RELAY_ACCOUNT_SECRET]),
 * never here.
 *
 * The seam is in commonMain so the shared Settings UI can render the desktop
 * "Mobile Agent Connection" section against [state]; the real implementation is
 * desktop-only ([com.contextsolutions.mobileagent.preferences.DesktopSubscriptionPreferences]).
 * Mobile binds [NoOpSubscriptionPreferences], which always reports "no
 * subscription" (the phone never purchases — it only scans).
 */
interface SubscriptionPreferences {
    fun state(): SubscriptionState
    fun stateFlow(): Flow<SubscriptionState>
    fun update(state: SubscriptionState)
    fun clear()
}

/**
 * Snapshot of the local subscription record. [status] mirrors the gateway's
 * license behavior (`valid`/`grace`/`revoked`/`suspended`/`none`/`unknown`).
 */
data class SubscriptionState(
    val accountId: String = "",
    val subscriptionId: String = "",
    val licenseId: String = "",
    val status: String = STATUS_NONE,
    val currentPeriodEndEpochSec: Long = 0L,
) {
    /** True once an account has been provisioned locally (drives the launch check). */
    val hasAccount: Boolean get() = accountId.isNotBlank()

    /** True when the relay may be used: a valid or in-grace subscription. */
    val isActive: Boolean get() = status == STATUS_VALID || status == STATUS_GRACE

    companion object {
        const val STATUS_NONE = "none"
        const val STATUS_VALID = "valid"
        const val STATUS_GRACE = "grace"
        const val STATUS_REVOKED = "revoked"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_UNKNOWN = "unknown"

        val EMPTY = SubscriptionState()
    }
}

/** Mobile/iOS binding: the phone never subscribes, so this is always empty. */
class NoOpSubscriptionPreferences : SubscriptionPreferences {
    private val state = MutableStateFlow(SubscriptionState.EMPTY)
    override fun state(): SubscriptionState = state.value
    override fun stateFlow(): Flow<SubscriptionState> = state.asStateFlow()
    override fun update(state: SubscriptionState) { /* no-op */ }
    override fun clear() { /* no-op */ }
}
