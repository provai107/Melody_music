package com.braveplayer.filter.model

/**
 * Immutable point-in-time snapshot of network filtering statistics.
 */
data class FilterStatistics(
    val totalRequestsEvaluated: Long,
    val blockedRequests: Long,
    val allowedRequests: Long,
    val allowlistedRequests: Long,
    val audioStreamBypasses: Long,
    val lastBlockedTimestampMs: Long?,
    val topBlockedDomains: Map<String, Long>,
    val ruleHitCounts: Map<String, Long>
)
