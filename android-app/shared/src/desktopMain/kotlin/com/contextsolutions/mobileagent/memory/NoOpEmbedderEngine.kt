package com.contextsolutions.mobileagent.memory

/**
 * Phase-0 placeholder embedder (docs/DESKTOP_PORT_PLAN.md). Returns null so
 * MemoryRetriever / MemoryExtractor degrade to no-ops (PRD §3.2.4). Phase 5
 * replaces this with [OnnxEmbedderEngine].
 */
class NoOpEmbedderEngine : EmbedderEngine {
    override val isLoaded: Boolean = false
    override suspend fun warmUp(): EmbedderAccelerator? = null
    override suspend fun embed(text: String): EmbedderOutput? = null
    override suspend fun unload() {}
}
