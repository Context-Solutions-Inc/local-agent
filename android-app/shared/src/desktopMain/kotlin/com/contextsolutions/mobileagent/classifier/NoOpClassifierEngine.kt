package com.contextsolutions.mobileagent.classifier

/**
 * Phase-0 placeholder classifier (docs/DESKTOP_PORT_PLAN.md). Reports "not loaded"
 * and returns null from [classify], which [PreflightRouter] treats as a defined
 * degradation mode — the agent simply falls through to the LLM without preflight
 * search routing. Phase 5 replaces this with [OnnxClassifierEngine].
 */
class NoOpClassifierEngine : ClassifierEngine {
    override val isLoaded: Boolean = false
    override suspend fun warmUp(): ClassifierAccelerator? = null
    override suspend fun classify(inputIds: LongArray, attentionMask: LongArray): ClassifierOutput? = null
    override suspend fun unload() {}
}
