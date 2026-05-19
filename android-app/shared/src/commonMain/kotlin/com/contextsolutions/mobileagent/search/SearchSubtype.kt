package com.contextsolutions.mobileagent.search

/**
 * The five vertical channels the pre-flight router can dispatch search work to
 * once the classifier decides "search is needed". Selected by
 * [SearchSubtypeDetector] from the user's literal query (regex/keyword), then
 * threaded onto [com.contextsolutions.mobileagent.classifier.PreflightDecision.FireSearch]
 * so the agent loop picks the right [com.contextsolutions.mobileagent.search.vertical.VerticalSearchAdapter].
 *
 * The classifier itself is unchanged in this PR — it still emits the binary
 * `p_search_required` only. Subtype is a post-classification refinement; a
 * v1.x retrain can promote it into a fourth model head once telemetry shows
 * the regex path misroutes too often (see `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md`).
 */
enum class SearchSubtype {
    GENERAL,
    NEWS,
    WEATHER,
    SPORTS,
    FINANCE,
}
