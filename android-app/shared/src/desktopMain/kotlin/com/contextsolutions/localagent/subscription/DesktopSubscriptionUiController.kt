package com.contextsolutions.localagent.subscription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Desktop [SubscriptionUiController] — wraps [RelaySubscriptionService] so the
 * shared Settings UI can launch checkout / open the portal (PR #74). [onUpgrade]
 * runs on [scope] because [RelaySubscriptionService.startCheckout] is suspending.
 */
class DesktopSubscriptionUiController(
    private val service: RelaySubscriptionService,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) : SubscriptionUiController {
    override val available: Boolean get() = service.isConfigured
    override fun stateFlow(): Flow<SubscriptionState> = service.stateFlow()
    override fun onUpgrade() {
        scope.launch { service.startCheckout()?.let { logger("checkout: $it") } }
    }
    override fun onManage() {
        scope.launch { service.openSubscriptionSettings() }
    }
}
