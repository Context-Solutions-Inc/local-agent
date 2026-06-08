package com.contextsolutions.mobileagent.subscription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * UI seam for paid "anywhere access" (PR #74), letting the shared Settings screen
 * drive the desktop checkout/manage actions without depending on the desktop-only
 * [RelaySubscriptionService]. Mobile binds [NoOpSubscriptionUiController]
 * ([available] = false ⇒ the upgrade UI is hidden on the phone).
 */
interface SubscriptionUiController {
    /** True only on a desktop with a configured gateway — gates the upgrade UI. */
    val available: Boolean

    fun stateFlow(): Flow<SubscriptionState>

    /** Begin (or resume) the Stripe Checkout flow; fire-and-forget. */
    fun onUpgrade()

    /** Open the Stripe Customer Portal / account page. */
    fun onManage()
}

/** Mobile/iOS binding: the phone never subscribes. */
class NoOpSubscriptionUiController : SubscriptionUiController {
    override val available: Boolean = false
    override fun stateFlow(): Flow<SubscriptionState> = flowOf(SubscriptionState.EMPTY)
    override fun onUpgrade() { /* no-op */ }
    override fun onManage() { /* no-op */ }
}
